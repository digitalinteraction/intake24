package uk.ac.ncl.openlab.intake24.foodsql.admin

import java.util.UUID

import scala.Left
import scala.Right

import org.postgresql.util.PSQLException
import org.slf4j.LoggerFactory

import anorm.BatchSql
import anorm.Macro
import anorm.NamedParameter
import anorm.NamedParameter.symbol
import anorm.SQL
import anorm.SqlParser
import anorm.sqlToSimple
import uk.ac.ncl.openlab.intake24.FoodRecord
import uk.ac.ncl.openlab.intake24.InheritableAttributes
import uk.ac.ncl.openlab.intake24.LocalFoodRecord
import uk.ac.ncl.openlab.intake24.MainFoodRecord
import uk.ac.ncl.openlab.intake24.NewFood
import uk.ac.ncl.openlab.intake24.foodsql.SqlDataService
import uk.ac.ncl.openlab.intake24.foodsql.Util
import uk.ac.ncl.openlab.intake24.services.fooddb.admin.FoodsAdminService
import uk.ac.ncl.openlab.intake24.services.fooddb.errors.CodeError
import uk.ac.ncl.openlab.intake24.services.fooddb.errors.CreateError
import uk.ac.ncl.openlab.intake24.services.fooddb.errors.DatabaseError
import uk.ac.ncl.openlab.intake24.services.fooddb.errors.DuplicateCode
import uk.ac.ncl.openlab.intake24.services.fooddb.errors.UndefinedCode
import uk.ac.ncl.openlab.intake24.services.fooddb.errors.UpdateError
import uk.ac.ncl.openlab.intake24.services.fooddb.errors.VersionConflict

trait FoodsAdminServiceImpl extends FoodsAdminService with SqlDataService with AdminPortionSizeShared with AdminErrorMessagesShared {

  val logger = LoggerFactory.getLogger(classOf[FoodsAdminServiceImpl])

  private def foodCodePkConstraintFailedMessage(foodCode: String) =
    s"Food code $foodCode already exists. Duplicate food codes are not allowed."

  private val foodCodePkConstraintFailedCode = "duplicate_food_code"

  private val temporaryCodesExhausted = "temporary_codes_exhausted"

  private val temporaryCodesExhaustedMessage = "Cannot assign a temporary food code, tried F000 through F999 but none are available."

  /*  val foodsDeleteVersioned = """DELETE FROM foods WHERE code={food_code} AND version={version}::uuid"""

  val foodsLocalDelete = """DELETE FROM foods_local WHERE food_code={food_code} AND locale_id={locale_id}""" */

  private case class FoodRow(code: String, description: String, local_description: Option[String], food_group_id: Long)

  private val foodRowParser = Macro.namedParser[FoodRow]

  private case class FoodResultRow(version: UUID, code: String, description: String, local_description: Option[String], do_not_use: Option[Boolean], food_group_id: Long,
    same_as_before_option: Option[Boolean], ready_meal_option: Option[Boolean], reasonable_amount: Option[Int], local_version: Option[UUID])

  private case class NutrientTableRow(nutrient_table_id: String, nutrient_table_record_id: String)

  def foodRecord(code: String, locale: String): Either[CodeError, FoodRecord] = tryWithConnection {
    // This is divided into two queries because the portion size estimation method list
    // can be empty, and it's very awkward to handle this case with one big query
    // with a lot of replication
    implicit conn =>

      val psmResults =
        SQL("""|SELECT foods_portion_size_methods.id, method, description, image_url, use_for_recipes,
               |foods_portion_size_method_params.id as param_id, name as param_name, value as param_value
               |FROM foods_portion_size_methods LEFT JOIN foods_portion_size_method_params 
               |  ON foods_portion_size_methods.id = foods_portion_size_method_params.portion_size_method_id
               |WHERE food_code = {food_code} AND locale_id = {locale_id} ORDER BY param_id""".stripMargin)
          .on('food_code -> code, 'locale_id -> locale).executeQuery().as(psmResultRowParser.*)

      val portionSizeMethods = mkPortionSizeMethods(psmResults)

      val nutrientTableCodes =
        SQL("""SELECT nutrient_table_id, nutrient_table_record_id FROM foods_nutrient_mapping WHERE food_code = {food_code} AND locale_id = {locale_id}""")
          .on('food_code -> code, 'locale_id -> locale)
          .as(Macro.namedParser[NutrientTableRow].*).map {
            case NutrientTableRow(id, code) => (id -> code)
          }.toMap

      val foodQuery =
        """|SELECT code, description, local_description, do_not_use, food_group_id, same_as_before_option, ready_meal_option,
           |       reasonable_amount, foods.version as version, foods_local.version as local_version 
           |FROM foods 
           |     INNER JOIN foods_attributes ON foods.code = foods_attributes.food_code
           |     LEFT JOIN foods_local ON foods.code = foods_local.food_code AND foods_local.locale_id = {locale_id}
           |WHERE code = {food_code}""".stripMargin

      val foodRowParser = Macro.namedParser[FoodResultRow]

      SQL(foodQuery).on('food_code -> code, 'locale_id -> locale).executeQuery().as(foodRowParser.singleOpt) match {
        case Some(result) =>
          Right(FoodRecord(
            MainFoodRecord(result.version, result.code, result.description, result.food_group_id.toInt,
              InheritableAttributes(result.ready_meal_option, result.same_as_before_option, result.reasonable_amount)),
            LocalFoodRecord(result.local_version, result.local_description, result.do_not_use.getOrElse(false), nutrientTableCodes, portionSizeMethods)))
        case None => Left(UndefinedCode)
      }
  }

  private val foodInsertQuery = "INSERT INTO foods VALUES ({code}, {description}, {food_group_id}, {version}::uuid)"

  private val foodAttributesInsertQuery = "INSERT INTO foods_attributes VALUES (DEFAULT, {food_code}, {same_as_before_option}, {ready_meal_option}, {reasonable_amount})"

  private def truncateDescription(description: String, foodCode: String) = {
    if (description.length() > 128) {
      logger.warn(s"Description too long for food ${foodCode}, truncating:")
      logger.warn(description)
      description.take(128)
    } else
      description

  }

  def isFoodCode(code: String): Either[DatabaseError, Boolean] = tryWithConnection {
    implicit conn =>
      Right(SQL("""SELECT code FROM foods WHERE code={food_code}""").on('food_code -> code).executeQuery().as(SqlParser.str("code").*).nonEmpty)
  }

  def isFoodCodeAvailable(code: String): Either[DatabaseError, Boolean] = tryWithConnection {
    implicit conn =>
      Right(SQL("SELECT code FROM foods WHERE code={food_code}").on('food_code -> code).executeQuery().as(SqlParser.str("code").*).isEmpty)
  }

  def createFood(newFood: NewFood): Either[CreateError, Unit] = tryWithConnection {
    implicit conn =>
      conn.setAutoCommit(false)
      try {
        SQL(foodInsertQuery)
          .on('code -> newFood.code, 'description -> newFood.englishDescription, 'food_group_id -> newFood.groupCode, 'version -> UUID.randomUUID())
          .execute()

        SQL(foodAttributesInsertQuery)
          .on('food_code -> newFood.code, 'same_as_before_option -> newFood.attributes.sameAsBeforeOption,
            'ready_meal_option -> newFood.attributes.readyMealOption, 'reasonable_amount -> newFood.attributes.reasonableAmount).execute()

        conn.commit()

        Right(())
      } catch {
        case e: PSQLException =>

          e.getServerErrorMessage.getConstraint match {
            case "foods_code_pk" => Left(DuplicateCode)
            case _ => throw e
          }
      }
  }

  def createFoodWithTempCode(newFood: NewFood): Either[DatabaseError, String] = {
    def tryNextNumber(n: Int): Either[DatabaseError, String] = {
      if (n > 999)
        Left(DatabaseError(temporaryCodesExhaustedMessage, new RuntimeException(temporaryCodesExhaustedMessage)))
      else {
        val tempCode = "F%03d".format(n)
        createFood(newFood.copy(code = tempCode)) match {
          case Right(()) => Right(tempCode)
          case Left(DuplicateCode) => tryNextNumber(n + 1)
          case Left(DatabaseError(m, t)) => Left(DatabaseError(m, t))
        }
      }
    }

    tryNextNumber(0)
  }

  def createFoods(foods: Seq[NewFood]): Either[DatabaseError, Unit] = tryWithConnection {
    implicit conn =>

      if (foods.nonEmpty) {
        logger.info(s"Writing ${foods.size} new food records to database")

        conn.setAutoCommit(false)

        val foodParams = foods.flatMap {
          f =>
            Seq[NamedParameter]('code -> f.code, 'description -> truncateDescription(f.englishDescription, f.code), 'food_group_id -> f.groupCode, 'version -> UUID.randomUUID())
        }

        BatchSql(foodInsertQuery, foodParams).execute()

        val foodAttributeParams =
          foods.flatMap(f => Seq[NamedParameter]('food_code -> f.code, 'same_as_before_option -> f.attributes.sameAsBeforeOption,
            'ready_meal_option -> f.attributes.readyMealOption, 'reasonable_amount -> f.attributes.reasonableAmount))

        BatchSql(foodAttributesInsertQuery, foodAttributeParams).execute()

        conn.commit()

        Right(())
      } else {
        logger.warn("Create foods request with empty foods list")
        Right(())
      }
  }

  def deleteAllFoods(): Either[DatabaseError, Unit] = tryWithConnection {
    implicit conn =>
      SQL("DELETE FROM foods").execute()
      Right(())
  }

  def deleteFood(foodCode: String): Either[CodeError, Unit] = tryWithConnection {
    implicit conn =>
      val rowsAffected = SQL("""DELETE FROM foods WHERE code={food_code}""").on('food_code -> foodCode).executeUpdate()

      if (rowsAffected == 1)
        Right(())
      else
        Left(UndefinedCode)
  }

  val foodLocalInsertQuery = "INSERT INTO foods_local VALUES({food_code}, {locale_id}, {local_description}, {do_not_use}, {version}::uuid)"

  val foodNutrientMappingInsertQuery = "INSERT INTO foods_nutrient_mapping VALUES ({food_code}, {locale_id}, {nutrient_table_id}, {nutrient_table_code})"

  val foodPsmInsertQuery = "INSERT INTO foods_portion_size_methods VALUES(DEFAULT, {food_code}, {locale_id}, {method}, {description}, {image_url}, {use_for_recipes})"

  val foodPsmParamsInsertQuery = "INSERT INTO foods_portion_size_method_params VALUES(DEFAULT, {portion_size_method_id}, {name}, {value})"

  def createLocalFoods(localFoodRecordsq: Map[String, LocalFoodRecord], locale: String): Either[DatabaseError, Unit] = tryWithConnection {
    implicit conn =>
      if (localFoodRecordsq.nonEmpty) {

        val localFoodRecordsSeq = localFoodRecordsq.toSeq

        logger.info(s"Writing ${localFoodRecordsSeq.size} new local food records to database")

        conn.setAutoCommit(false)

        val foodLocalParams = localFoodRecordsSeq.flatMap {
          case (code, local) =>
            Seq[NamedParameter]('food_code -> code, 'locale_id -> locale, 'local_description -> local.localDescription.map(d => truncateDescription(d, code)),
              'do_not_use -> local.doNotUse, 'version -> local.version.getOrElse(UUID.randomUUID()))
        }.toSeq

        BatchSql(foodLocalInsertQuery, foodLocalParams).execute()

        val foodNutritionTableParams =
          localFoodRecordsSeq.flatMap {
            case (code, local) =>
              local.nutrientTableCodes.map {
                case (table_id, table_code) => Seq[NamedParameter]('food_code -> code, 'locale_id -> locale, 'nutrient_table_id -> table_id, 'nutrient_table_code -> table_code)
              }
          }.toSeq

        if (foodNutritionTableParams.nonEmpty)
          BatchSql(foodNutrientMappingInsertQuery, foodNutritionTableParams).execute()

        val psmParams =
          localFoodRecordsSeq.flatMap {
            case (code, local) =>
              local.portionSize.map(ps => Seq[NamedParameter]('food_code -> code, 'locale_id -> locale, 'method -> ps.method, 'description -> ps.description, 'image_url -> ps.imageUrl, 'use_for_recipes -> ps.useForRecipes))
          }.toSeq

        if (psmParams.nonEmpty) {
          val ids = Util.batchKeys(BatchSql(foodPsmInsertQuery, psmParams))

          val psmParamParams = localFoodRecordsSeq.flatMap(_._2.portionSize).zip(ids).flatMap {
            case (psm, id) => psm.parameters.map(param => Seq[NamedParameter]('portion_size_method_id -> id, 'name -> param.name, 'value -> param.value))
          }

          if (psmParamParams.nonEmpty)
            BatchSql(foodPsmParamsInsertQuery, psmParamParams).execute()
        }

        conn.commit()
        Right(())

      } else {
        logger.warn("Create local foods request with empty foods list")
        Right(())
      }
  }

  def updateMainFoodRecord(foodCode: String, foodBase: MainFoodRecord): Either[UpdateError, Unit] = tryWithConnection {
    implicit conn =>
      conn.setAutoCommit(false)

      try {
        SQL("DELETE FROM foods_attributes WHERE food_code={food_code}").on('food_code -> foodCode).execute()

        SQL(foodAttributesInsertQuery)
          .on('food_code -> foodCode, 'same_as_before_option -> foodBase.attributes.sameAsBeforeOption,
            'ready_meal_option -> foodBase.attributes.readyMealOption, 'reasonable_amount -> foodBase.attributes.reasonableAmount).execute()

        val rowsAffected = SQL("UPDATE foods SET code = {new_code}, description={description}, food_group_id={food_group_id}, version={new_version}::uuid WHERE code={food_code} AND version={base_version}::uuid)")
          .on('food_code -> foodCode, 'base_version -> foodBase.version,
            'new_version -> UUID.randomUUID(), 'new_code -> foodBase.code, 'description -> foodBase.englishDescription, 'food_group_id -> foodBase.groupCode)
          .executeUpdate()

        if (rowsAffected == 1) {
          conn.commit()
          Right(())
        } else
          Left(VersionConflict)
      } catch {
        case e: PSQLException => {
          e.getServerErrorMessage.getConstraint match {
            case "foods_attributes_food_code_fk" => Left(UndefinedCode)
            case _ => throw e
          }
        }
      }
  }

  def updateLocalFoodRecord(foodCode: String, locale: String, foodLocal: LocalFoodRecord): Either[UpdateError, Unit] = tryWithConnection {
    implicit conn =>
      conn.setAutoCommit(false)

      try {
        SQL("DELETE FROM foods_nutrient_mapping WHERE food_code={food_code} AND locale_id={locale_id}")
          .on('food_code -> foodCode, 'locale_id -> locale).execute()

        SQL("DELETE FROM foods_portion_size_methods WHERE food_code={food_code} AND locale_id={locale_id}")
          .on('food_code -> foodCode, 'locale_id -> locale).execute()

        if (foodLocal.nutrientTableCodes.nonEmpty) {
          val nutrientTableCodesParams = foodLocal.nutrientTableCodes.flatMap {
            case (table_id, table_code) => Seq[NamedParameter]('food_code -> foodCode, 'locale_id -> locale, 'nutrient_table_id -> table_id, 'nutrient_table_code -> table_code)
          }.toSeq

          BatchSql(foodNutrientMappingInsertQuery, nutrientTableCodesParams).execute()
        }

        if (foodLocal.portionSize.nonEmpty) {
          val psmParams = foodLocal.portionSize.flatMap(ps => Seq[NamedParameter]('food_code -> foodCode, 'locale_id -> locale, 'method -> ps.method, 'description -> ps.description, 'image_url -> ps.imageUrl, 'use_for_recipes -> ps.useForRecipes))

          val psmKeys = Util.batchKeys(BatchSql(foodPsmInsertQuery, psmParams))

          val psmParamParams = foodLocal.portionSize.zip(psmKeys).flatMap {
            case (psm, id) => psm.parameters.flatMap(param => Seq[NamedParameter]('portion_size_method_id -> id, 'name -> param.name, 'value -> param.value))
          }

          if (psmParamParams.nonEmpty)
            BatchSql(foodPsmParamsInsertQuery, psmParamParams).execute()
        }

        foodLocal.version match {
          case Some(version) => {

            val rowsAffected = SQL("UPDATE foods_local SET version = {new_version}::uuid, local_description = {local_description}, do_not_use = {do_not_use} WHERE food_code = {food_code} AND locale_id = {locale_id} AND version = {base_version}::uuid")
              .on('food_code -> foodCode, 'locale_id -> locale, 'base_version -> foodLocal.version, 'new_version -> UUID.randomUUID(), 'local_description -> foodLocal.localDescription, 'do_not_use -> foodLocal.doNotUse)
              .executeUpdate()

            if (rowsAffected == 1) {
              conn.commit()
              Right(())
            } else
              Left(VersionConflict)
          }
          case None => {
            try {
              SQL(foodLocalInsertQuery)
                .on('food_code -> foodCode, 'locale_id -> locale, 'local_description -> foodLocal.localDescription, 'do_not_use -> foodLocal.doNotUse, 'version -> UUID.randomUUID())
                .execute()
              conn.commit()

              Right(())
            } catch {
              case e: PSQLException =>
                if (e.getServerErrorMessage.getConstraint == "foods_local_pk") {
                  Left(VersionConflict)
                } else
                  throw e
            }
          }
        }

      } catch {
        case e: PSQLException => {
          e.getServerErrorMessage.getConstraint match {
            case "foods_nutrient_tables_food_code_fk" | "foods_portion_size_methods_food_id_fk" | "foods_local_food_code_fk" => Left(UndefinedCode)
            case _ => throw e
          }
        }
      }
  }

}