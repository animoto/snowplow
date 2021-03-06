/*
 * Copyright (c) 2014 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.snowplowanalytics.snowplow.enrich
package hadoop
package inputs

// Java
import java.util.UUID

// Joda-Time
import org.joda.time.{DateTime, DateTimeZone}
import org.joda.time.format.DateTimeFormat

// Scala
import scala.util.Try

// Scalaz
import scalaz._
import Scalaz._

// Snowplow Utils
import com.snowplowanalytics.util.Tap._

// Snowplow Common Enrich
import common._
import outputs.CanonicalOutput

/**
 * A loader for Snowplow enriched events - i.e. the
 * TSV files generated by the Snowplow Enrichment
 * process.
 */
object EnrichedEventLoader {

  private val RedshiftTstampFormat = DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(DateTimeZone.UTC)

  private val FieldCount = 104

  private object FieldIndexes { // 0-indexed
    val collectorTstamp = 2
    val eventId = 6
    val contexts = 47
    val ueProperties = 54
  }

  /**
   * Converts the source string into a 
   * ValidatedCanonicalOutput. Note that
   * this loads the bare minimum required
   * for shredding - basically four fields.
   *
   * @param line A line of data to convert
   * @return either a set of validation
   *         Failures or a CanonicalOutput
   *         Success.
   */
  // TODO: potentially in the future this could be replaced by some
  // kind of Scalding pack()
  def toEnrichedEvent(line: String): ValidatedCanonicalOutput = {

    val fields = line.split("\t").map(f => if (f == "") null else f)
    val len = fields.length
    if (len < FieldCount)
      return s"Line does not match Snowplow enriched event (expected ${FieldCount}+ fields; found $len)".failNel[CanonicalOutput]

    val event = new CanonicalOutput().tap { e =>
      e.contexts = fields(FieldIndexes.contexts)
      e.ue_properties = fields(FieldIndexes.ueProperties)
    }

    // Get and validate the event ID
    val eventId = validateUuid("event_id", fields(FieldIndexes.eventId))
    for (id <- eventId) { event.event_id = id }

    // Get and validate the collector timestamp
    val collectorTstamp = validateTimestamp("collector_tstamp", fields(FieldIndexes.collectorTstamp))
    for (tstamp <- collectorTstamp) { event.collector_tstamp = tstamp }

    (eventId.toValidationNel |@| collectorTstamp.toValidationNel) {
      (_,_) => event
    }
  }

  /**
   * Validates that the given field contains a valid UUID.
   *
   * @param field The name of the field being validated
   * @param str The String hopefully containing a UUID
   * @return a Scalaz ValidatedString containing either
   *         the original String on Success, or an error
   *         String on Failure.
   */
  private def validateUuid(field: String, str: String): ValidatedString = {

    def check(s: String)(u: UUID): Boolean = (u != null && s == u.toString)
    val uuid = Try(UUID.fromString(str)).toOption.filter(check(str))
    uuid match {
      case Some(_) => str.success
      case None    => s"Field [$field]: [$str] is not a valid UUID".fail
    }
  }

  /**
   * Validates that the given field contains a valid
   * (Redshift/Postgres-compatible) timestamp.
   *
   * @param field The name of the field being validated
   * @param str The String hopefully containing a
   *        Redshift/PG-compatible timestamp
   * @return a Scalaz ValidatedString containing either
   *         the original String on Success, or an error
   *         String on Failure.
   */
  private def validateTimestamp(field: String, str: String): ValidatedString =
    try {
      val _ = RedshiftTstampFormat.parseDateTime(str)
      str.success
    } catch {
      case e: Throwable =>
        s"Field [$field]: [$str] is not in the expected Redshift/Postgres timestamp format".fail
    }
}
