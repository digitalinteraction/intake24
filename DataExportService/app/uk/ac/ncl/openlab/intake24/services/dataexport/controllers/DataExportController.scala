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

package uk.ac.ncl.openlab.intake24.services.dataexport.controllers

import java.time._
import java.time.format.{DateTimeFormatter, DateTimeParseException}

import cats.data.EitherT
import cats.instances.future._
import javax.inject.Inject
import org.slf4j.LoggerFactory
import play.api.Configuration
import play.api.mvc.{BaseController, ControllerComponents, PlayBodyParsers}
import uk.ac.ncl.openlab.intake24.api.data.ErrorDescription
import uk.ac.ncl.openlab.intake24.play.utils.{DatabaseErrorHandler, JsonBodyParser, JsonUtils}
import uk.ac.ncl.openlab.intake24.security.authorization.Intake24RestrictedActionBuilder
import uk.ac.ncl.openlab.intake24.services.dataexport._
import uk.ac.ncl.openlab.intake24.services.fooddb.admin.FoodGroupsAdminService
import uk.ac.ncl.openlab.intake24.services.systemdb.Roles
import uk.ac.ncl.openlab.intake24.services.systemdb.admin._

import scala.concurrent.{ExecutionContext, Future}
import io.circe.generic.auto._
import uk.ac.ncl.openlab.intake24.services.dataexport.views.html.DataExportNotification

case class NewExportTaskInfo(taskId: Long)

case class NewScheduledTaskRequest(daysOfWeek: Int, time: LocalTime, timeZone: String, period: Option[Int], action: String, actionConfig: String)

class DataExportController @Inject()(configuration: Configuration,
                                     service: DataExportService,
                                     surveyAdminService: SurveyAdminService,
                                     foodGroupsAdminService: FoodGroupsAdminService,
                                     dataExporter: SingleThreadedDataExporter,
                                     s3Uploader: DataExportS3Uploader,
                                     exportScheduler: ScheduledDataExportService,
                                     ndnsGroupsCache: NdnsCompoundsFoodGroupsCache,
                                     emailSender: EmailSender,
                                     rab: Intake24RestrictedActionBuilder,
                                     playBodyParsers: PlayBodyParsers,
                                     jsonBodyParser: JsonBodyParser,
                                     val controllerComponents: ControllerComponents,
                                     implicit val executionContext: ExecutionContext) extends BaseController
  with DatabaseErrorHandler {

  val logger = LoggerFactory.getLogger(classOf[DataExportController])

  val urlExpirationTimeMinutes = configuration.get[Int](s"intake24.asyncDataExporter.s3.urlExpirationTimeMinutes")

  def getSurveySubmissions(surveyId: String, dateFrom: String, dateTo: String, offset: Int, limit: Int) = rab.restrictToRoles(Roles.superuser, Roles.surveyAdmin, Roles.surveyStaff(surveyId))(playBodyParsers.empty) {
    _ =>
      Future {

        try {
          val parsedFrom = ZonedDateTime.parse(dateFrom)
          val parsedTo = ZonedDateTime.parse(dateTo)

          translateDatabaseResult(service.getSurveySubmissions(surveyId, Some(parsedFrom), Some(parsedTo), offset, limit, None))
        } catch {
          case e: DateTimeParseException => BadRequest(toJsonString(ErrorDescription("DateFormat", "Failed to parse date parameter. Expected a UTC date in ISO 8601 format, e.g. '2017-02-15T16:40:30Z'.")))
        }
      }
  }

  def getMySurveySubmissions(surveyId: String) = rab.restrictToRoles(Roles.surveyRespondent(surveyId))(playBodyParsers.empty) {
    request =>
      Future {

        val respondentId = request.subject.userId
        val includeFoodGroups = request.getQueryString("compoundFoodGroups").isDefined

        try {

          if (includeFoodGroups) {
            val submissionsWithFoodGroups = for (submissions <- service.getSurveySubmissions(surveyId, None, None, 0, Int.MaxValue, Some(respondentId));
                                                 withFoodGroups <- ndnsGroupsCache.addFoodGroups(submissions))
              yield withFoodGroups

            translateDatabaseResult(submissionsWithFoodGroups)
          }
          else
            translateDatabaseResult(service.getSurveySubmissions(surveyId, None, None, 0, Int.MaxValue, Some(respondentId)))

        } catch {
          case e: DateTimeParseException => BadRequest(toJsonString(ErrorDescription("DateFormat", "Failed to parse date parameter. Expected a UTC date in ISO 8601 format, e.g. '2017-02-15T16:40:30Z'.")))
        }
      }
  }

  def getSurveySubmissionsAsCSV(surveyId: String, dateFrom: String, dateTo: String) = rab.restrictToRoles(Roles.superuser, Roles.surveyAdmin, Roles.surveyStaff(surveyId))(playBodyParsers.empty) {
    request =>
      Future {

        try {
          val parsedFrom = ZonedDateTime.parse(dateFrom)
          val parsedTo = ZonedDateTime.parse(dateTo)
          val forceBOM = request.getQueryString("forceBOM").isDefined

          val data = for (params <- surveyAdminService.getSurveyParameters(surveyId).right;
                          localNutrients <- surveyAdminService.getLocalNutrientTypes(params.localeId).right;
                          dataScheme <- surveyAdminService.getCustomDataScheme(params.schemeId).right;
                          foodGroups <- foodGroupsAdminService.listFoodGroups(params.localeId).right;
                          submissions <- service.getSurveySubmissions(surveyId, Some(parsedFrom), Some(parsedTo), 0, Integer.MAX_VALUE, None).right) yield ((localNutrients, dataScheme, foodGroups, submissions))

          data match {
            case Right((localNutrients, dataScheme, foodGroups, submissions)) =>
              SurveyCSVExporter.exportSurveySubmissions(dataScheme, foodGroups, localNutrients, submissions, forceBOM) match {
                case Right(csvFile) =>
                  val dateStamp = DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(LocalDateTime.ofInstant(Clock.systemUTC().instant(), ZoneId.systemDefault).withNano(0)).replace(":", "-").replace("T", "-")

                  Ok.sendFile(csvFile, fileName = _ => s"intake24-$surveyId-data-$dateStamp.csv", onClose = () => csvFile.delete()).as(if (forceBOM) "application/octet-stream" else "text/csv;charset=utf-8")
                case Left(exportError) => InternalServerError(toJsonString(ErrorDescription("ExportError", exportError)))
              }
            case Left(databaseError) => translateDatabaseError(databaseError)
          }


        } catch {
          case e: DateTimeParseException => BadRequest(toJsonString(ErrorDescription("DateFormat", "Failed to parse date parameter. Expected a UTC date in ISO 8601 format, e.g. '2017-02-15T16:40:30Z'.")))
        }
      }
  }

  //val body = DataExportNotification(task.userName, task.surveyId, downloadUrl, config.s3UrlExpirationTimeMinutes.toInt / 60)

  //val message = Email(, , Seq(email), None, Some(body.toString()))

  def downloadAvailableMessage(surveyId: String, url: String) =
    (userProfile: UserProfile) => DataExportNotification(userProfile.name, surveyId, url, urlExpirationTimeMinutes / 60).toString()

  def queueCSVExportForDownload(surveyId: String, dateFrom: String, dateTo: String) = rab.restrictToRoles(Roles.superuser, Roles.surveyAdmin, Roles.surveyStaff(surveyId))(playBodyParsers.empty) {
    request =>
      try {
        val parsedFrom = ZonedDateTime.parse(dateFrom)
        val parsedTo = ZonedDateTime.parse(dateTo)
        val forceBOM = request.getQueryString("forceBOM").isDefined

        dataExporter.queueCsvExport(request.subject.userId, surveyId, parsedFrom, parsedTo, forceBOM, "download").map {
          handle =>

            val dateStamp = DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(LocalDateTime.ofInstant(Clock.systemUTC().instant(), ZoneId.systemDefault).withNano(0)).replace(":", "-").replace("T", "-")

            (for (exportTaskHandle <- EitherT(Future.successful(handle));
                  s3url <- EitherT(s3Uploader.upload(exportTaskHandle, s"intake24-$surveyId-data-${exportTaskHandle.id}-$dateStamp.csv"));
                  _ <- EitherT(emailSender.sendHtml(request.subject.userId, s"Your Intake24 survey ($surveyId) data is available for download",
                    "Intake24 <support@intake24.co.uk>", downloadAvailableMessage(surveyId, s3url.toString()))))
              yield ()).value.map {
              case Right(()) => ()
              case Left(e) => logger.error(s"Manual export request failed!", e.exception)
            }

            translateDatabaseResult(handle.map(h => NewExportTaskInfo(h.id)))
        }

      } catch {
        case _: DateTimeParseException =>
          Future.successful(BadRequest(toJsonString(ErrorDescription("DateFormat", "Failed to parse date parameter. Expected a UTC date in ISO 8601 format, e.g. '2017-02-15T16:40:30Z'."))))
      }
  }


  case class GetExportTaskStatusResult(activeTasks: Seq[ExportTaskInfo])

  def getExportTaskStatus(surveyId: String) = rab.restrictToRoles(Roles.superuser, Roles.surveyAdmin, Roles.surveyStaff(surveyId))(playBodyParsers.empty) {
    request =>
      Future {
        translateDatabaseResult(service.getActiveExportTasks(surveyId, request.subject.userId).right.map(GetExportTaskStatusResult(_)))
      }
  }

  def scheduleExport(surveyId: String) = rab.restrictToRoles(Roles.superuser, Roles.surveyAdmin)(jsonBodyParser.parse[NewScheduledTaskRequest]) {
    request =>
      Future {
        val r = request.body
        translateDatabaseResult(exportScheduler.createScheduledTask(request.subject.userId, surveyId, r.period, r.daysOfWeek, r.time, r.timeZone, r.action, r.actionConfig))
      }

  }
}