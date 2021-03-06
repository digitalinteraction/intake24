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

package controllers

import javax.inject.Inject

import io.circe.generic.auto._
import play.api.mvc.{BaseController, ControllerComponents}
import security.Intake24RestrictedActionBuilder
import uk.ac.ncl.openlab.intake24.services.fooddb.admin.LocalesAdminService

import scala.concurrent.{ExecutionContext, Future}

class LocalesAdminController @Inject()(service: LocalesAdminService,
                                       foodAuthChecks: FoodAuthChecks,
                                       rab: Intake24RestrictedActionBuilder,
                                       val controllerComponents: ControllerComponents,
                                       implicit val executionContext: ExecutionContext) extends BaseController with DatabaseErrorHandler {

  def listLocales() = rab.restrictAccess(foodAuthChecks.canReadLocales) {
    _ =>
      Future {
        translateDatabaseResult(service.listLocales())
      }
  }

  def getLocale(id: String) = rab.restrictAccess(foodAuthChecks.canReadLocales) {
    _ =>
      Future {
        translateDatabaseResult(service.getLocale(id))
      }
  }
}
