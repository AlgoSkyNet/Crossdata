/*
 * Copyright (C) 2015 Stratio (http://stratio.com)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.stratio.crossdata.driver

import akka.actor.{ActorSelection, ActorSystem}
import akka.contrib.pattern.ClusterClient
import akka.util.Timeout
import com.stratio.crossdata.common.metadata.FieldMetadata
import com.stratio.crossdata.common.result._
import com.stratio.crossdata.common.{SQLCommand, SQLResult}
import com.stratio.crossdata.driver.actor.ProxyActor
import com.stratio.crossdata.driver.config.DriverConfig
import com.stratio.crossdata.driver.utils.RetryPolitics
import com.typesafe.config.ConfigValueFactory
import org.apache.log4j.Logger
import org.apache.spark.sql.crossdata.metadata.DataTypesUtils

import scala.collection.JavaConversions._
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.language.postfixOps
import scala.util.Try

object Driver extends DriverConfig {
  override lazy val logger = Logger.getLogger(getClass)
  val ActorsPath = "/user/receptionist"

  def apply(seedNodes: Option[java.util.List[String]] = None) =
    new Driver(seedNodes)

}

class Driver(seedNodes: Option[java.util.List[String]] = None) {

  import Driver._

  private lazy val logger = Driver.logger

  private val proxyActor = {
    val finalConfig = seedNodes.fold(
      Driver.config
    ) { seedNodes =>
      Driver.config.withValue("akka.cluster.seed-nodes", ConfigValueFactory.fromAnyRef(seedNodes))
    }
    val system = ActorSystem("CrossdataServerCluster", finalConfig)

    if (logger.isDebugEnabled) {
      system.logConfiguration()
    }

    val contactPoints = finalConfig.getStringList("akka.cluster.seed-nodes").map(_ + ActorsPath)
    val initialContacts: Set[ActorSelection] = contactPoints.map(system.actorSelection).toSet

    logger.debug("Initial contacts: " + initialContacts)
    val clusterClientActor = system.actorOf(ClusterClient.props(initialContacts), "remote-client")
    system.actorOf(ProxyActor.props(clusterClientActor, this), "proxy-actor")
  }

  // TODO syncQuery and asynQuery should be private when the driver get improved
  def syncQuery(sqlCommand: SQLCommand, timeout: Timeout = Timeout(10 seconds), retries: Int = 2): SQLResult = {
    Try {
      Await.result(asyncQuery(sqlCommand, timeout, retries), timeout.duration * retries)
    } getOrElse ErrorResult(sqlCommand.queryId, s"Not found answer to query ${sqlCommand.query}. Timeout was exceed.")
  }

  def asyncQuery(sqlCommand: SQLCommand, timeout: Timeout = Timeout(10 seconds), retries: Int = 2): Future[SQLResult] = {
    RetryPolitics.askRetry(proxyActor, sqlCommand, timeout, retries)
  }


  def listDatabases(): Seq[String] = {
    // TODO create command in XD Parser => syncQuery(SQLCommand("SHOW DATABASES"))
    ???
  }

  def listTables(databaseName: Option[String] = None): Seq[String] = {
    syncQuery(SQLCommand(s"SHOW TABLES ${databaseName.fold("")("IN " + _)}")) match {
      case SuccessfulQueryResult(_, result) =>
        result.map(row => row.getString(0))
      case ErrorResult(_, message, Some(cause)) =>
        throw new RuntimeException(message, cause)
      case ErrorResult(_, message, _) =>
        throw new RuntimeException(message)
      // TODO manage exceptions
    }
  }

  def describeTable(database: Option[String], tableName: String): Seq[FieldMetadata] = {
    syncQuery(SQLCommand(s"DESCRIBE ${database.map(_ + ".").getOrElse("")}$tableName")) match {
      case SuccessfulQueryResult(_, result) =>
        result.map(row => FieldMetadata(row.getString(0), DataTypesUtils.toDataType(row.getString(1))))
      case ErrorResult(_, message, Some(cause)) =>
        throw new RuntimeException(message, cause)
      case ErrorResult(_, message, _) =>
        throw new RuntimeException(message)
    }
  }

}