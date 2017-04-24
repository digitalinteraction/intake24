package uk.ac.ncl.openlab.intake24.services.systemdb.admin

import java.time.ZonedDateTime

import uk.ac.ncl.openlab.intake24.errors._

case class NewRespondentWithPhysicalData(externalId: String, name: Option[String], email: Option[String],
                                         phone: Option[String], sex: Option[String], birthdate: Option[ZonedDateTime],
                                         weight: Option[Double], height: Option[Double])

case class NewRespondentIds(userId: Long, externalId: String, urlAuthToken: String)


// Binds users admin service and physical data service together
trait UsersSupportService {

  def createRespondentsWithPhysicalData(surveyId: String, newUsers: Seq[NewRespondentWithPhysicalData]): Either[DependentCreateError, Seq[NewRespondentIds]]
}