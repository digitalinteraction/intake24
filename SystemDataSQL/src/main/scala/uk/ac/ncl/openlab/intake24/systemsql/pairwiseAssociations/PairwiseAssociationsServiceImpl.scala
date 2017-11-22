package uk.ac.ncl.openlab.intake24.systemsql.pairwiseAssociations

import java.time.{ZoneId, ZonedDateTime}
import javax.inject.{Inject, Singleton}

import org.slf4j.LoggerFactory
import uk.ac.ncl.openlab.intake24.pairwiseAssociationRules.PairwiseAssociationRules
import uk.ac.ncl.openlab.intake24.services.systemdb.admin.{DataExportService, SurveyAdminService, SurveyParametersOut}
import uk.ac.ncl.openlab.intake24.services.systemdb.pairwiseAssociations.{PairwiseAssociationsDataService, PairwiseAssociationsService, PairwiseAssociationsServiceConfiguration, PairwiseAssociationsServiceSortTypes}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Promise

/**
  * Created by Tim Osadchiy on 04/10/2017.
  */

@Singleton
class PairwiseAssociationsServiceImpl @Inject()(settings: PairwiseAssociationsServiceConfiguration,
                                                dataService: PairwiseAssociationsDataService,
                                                surveyAdminService: SurveyAdminService,
                                                pairwiseAssociationsConfig: PairwiseAssociationsServiceConfiguration,
                                                dataExportService: DataExportService) extends PairwiseAssociationsService {

  private val logger = LoggerFactory.getLogger(getClass)

  private var associationRules = getAssociationRules()

  override def recommend(locale: String, items: Seq[String], sortType: String = PairwiseAssociationsServiceSortTypes.paRules): Seq[(String, Double)] = extractPairwiseRules(locale) { rules =>
    val params = rules.getParams()

    if (params.numberOfTransactions < settings.minimumNumberOfSurveySubmissions ||
      items.size < pairwiseAssociationsConfig.minInputSearchSize ||
      sortType == PairwiseAssociationsServiceSortTypes.popularity) {
      params.occurrences.map(o => o._1 -> o._2.toDouble).toSeq.sortBy(-_._2)
    } else {
      rules.recommend(items)
    }
  }.getOrElse(Nil)

  override def getOccurrences(locale: String): Map[String, Int] = {
    extractPairwiseRules(locale) { rules =>
      rules.getParams().occurrences
    }.getOrElse(Map[String, Int]())
  }

  override def addTransactions(surveyId: String, items: Seq[Seq[String]]): Unit = getValidSurvey(surveyId, "addTransactions").map { surveyParams =>
    associationRules.map { localeAr =>
      localeAr.get(surveyParams.localeId) match {
        case None =>
          val ar = PairwiseAssociationRules(None)
          ar.addTransactions(items)
          associationRules = Right(localeAr + (surveyParams.localeId -> ar))
        case Some(ar) => ar.addTransactions(items)
      }
      dataService.addTransactions(surveyParams.localeId, items)
    }
  }

  override def refresh(): Unit = {
    val graphProm = Promise[Map[String, PairwiseAssociationRules]]
    val t = new Thread(() => {
      logger.info("Refreshing Pairwise associations")
      logger.debug("Collecting surveys")
      val surveys = surveyAdminService.listSurveys()
      surveys.left.foreach(e => logger.error(e.exception.getMessage))

      logger.debug("Building new pairwise associations graph")
      val foldGraph = Map[String, PairwiseAssociationRules]().withDefaultValue(PairwiseAssociationRules(None))
      val graph = surveys.getOrElse(Nil)
        .foldLeft(foldGraph) { (foldGraph, survey) =>
          getSurveySubmissions(survey).foldLeft(foldGraph) { (foldGraph, submission) =>
            val localeRules = foldGraph(submission.locale)
            localeRules.addTransactions(submission.meals)
            foldGraph + (submission.locale -> localeRules)
          }
        }

      graphProm.success(graph)

    })
    for (
      graph <- graphProm.future;
      result <- dataService.writeAssociations(graph)
    ) yield {
      result match {
        case Left(e) =>
          logger.error(s"Failed to refresh PairwiseAssociations ${e.exception.getMessage}")
        case Right(_) =>
          logger.info(s"Successfully refreshed Pairwise associations")
          associationRules = getAssociationRules()
      }
    }
    t.start()
  }

  private def extractPairwiseRules[T](localeId: String)(f: PairwiseAssociationRules => T): Option[T] = associationRules.map { localeAr =>
    localeAr.get(localeId).map(rules => f(rules))
  } getOrElse (None)

  private def getSurveySubmissions(survey: SurveyParametersOut): Seq[Submission] = {
    val threadSleepFor = 10
    val submissionCount = getSubmissionCount(survey.id)
    if (surveyIsValid(survey.id, submissionCount, "getSurveySubmissions")) {
      Range(0, submissionCount, settings.rulesUpdateBatchSize).foldLeft(Seq[Submission]()) { (submissions, offset) =>
        Thread.sleep(threadSleepFor)
        submissions ++ dataExportService.getSurveySubmissions(survey.id, None, None, offset, settings.rulesUpdateBatchSize, None)
          .map { exportSubmissions =>
            Thread.sleep(threadSleepFor)
            exportSubmissions.map { expSubmission =>
              Thread.sleep(threadSleepFor)
              val meals = expSubmission.meals.map { meal => meal.foods.map(_.code) }
              Submission(survey.localeId, meals)
            }
          }.getOrElse(Nil)
      }
    } else {
      Nil
    }
  }

  private def getValidSurvey(surveyId: String, operationPrefix: String): Option[SurveyParametersOut] =
    if (surveyIsValid(surveyId, getSubmissionCount(surveyId), operationPrefix)) {
      surveyAdminService.getSurvey(surveyId) match {
        case Left(e) =>
          logger.error(e.exception.getMessage)
          None
        case Right(surveyParametersOut) => Some(surveyParametersOut)
      }
    } else {
      None
    }


  private def surveyIsValid(surveyId: String, submissionCount: Int, operationPrefix: String): Boolean =
    if (settings.ignoreSurveysContaining.exists(stopWord => surveyId.contains(stopWord))) {
      logger.warn(s"Survey $surveyId is ignored due to its' name at $operationPrefix")
      false
    } else if (submissionCount < settings.minimumNumberOfSurveySubmissions) {
      logger.warn(s"Survey $surveyId is ignored since it contains less than ${settings.minimumNumberOfSurveySubmissions} at $operationPrefix")
      false
    } else {
      true
    }

  private def getSubmissionCount(surveyId: String) = {
    val dateFrom = ZonedDateTime.of(1970, 1, 1, 0, 0, 0, 0, ZoneId.systemDefault())
    val dateTo = ZonedDateTime.now()
    dataExportService.getSurveySubmissionCount(surveyId, dateFrom, dateTo).getOrElse(0)
  }

  private def getAssociationRules() = {
    val associationRules = dataService.getAssociations()
    associationRules.left.foreach { dbError =>
      logger.error(dbError.exception.getMessage)
    }
    associationRules
  }

  private case class Submission(locale: String, meals: Seq[Seq[String]])

}