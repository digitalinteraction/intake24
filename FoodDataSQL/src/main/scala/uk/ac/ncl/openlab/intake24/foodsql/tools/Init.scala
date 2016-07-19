/*
This file is part of Intake24.

Copyright 2015, 2016 Newcastle University.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package uk.ac.ncl.openlab.intake24.foodsql.tools

import org.slf4j.LoggerFactory
import anorm.SqlParser
import org.rogach.scallop.ScallopConf
import anorm._



object Init extends App with WarningMessage with DatabaseConnection with SqlUtil {

  val logger = LoggerFactory.getLogger(getClass)
  
  displayWarningMessage("WARNING: THIS WILL DESTROY ALL DATA IN THE DATABASE!")
  
  val options = new ScallopConf(args) with DatabaseOptions
  
  options.afterInit()
  
  val dataSource = getDataSource(options)

  implicit val dbConn = dataSource.getConnection

  val initDbStatements = separateSqlStatements(stripComments(scala.io.Source.fromInputStream(getClass.getResourceAsStream("/sql/init_foods_db.sql"), "utf-8").mkString))

  logger.info("Dropping all tables and sequences")

  val dropTableStatements =
    SQL("""SELECT 'DROP TABLE IF EXISTS ' || tablename || ' CASCADE;' AS query FROM pg_tables WHERE schemaname='public'""")
      .executeQuery()
      .as(SqlParser.str("query").*)

  val dropSequenceStatements =
    SQL("""SELECT 'DROP SEQUENCE IF EXISTS ' || relname || ' CASCADE;' AS query FROM pg_class WHERE relkind = 'S'""")
      .executeQuery()
      .as(SqlParser.str("query").*)
      
  val clearDbStatements = dropTableStatements ++ dropSequenceStatements
  
  clearDbStatements.foreach {
    statement =>
      logger.debug(statement)
      SQL(statement).execute()
  }

  logger.info("Creating tables and default records")

  initDbStatements.foreach { statement =>
    logger.debug(statement)
    SQL(statement).execute()
  }

}