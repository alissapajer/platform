/*
 *  ____    ____    _____    ____    ___     ____ 
 * |  _ \  |  _ \  | ____|  / ___|  / _/    / ___|        Precog (R)
 * | |_) | | |_) | |  _|   | |     | |  /| | |  _         Advanced Analytics Engine for NoSQL Data
 * |  __/  |  _ <  | |___  | |___  |/ _| | | |_| |        Copyright (C) 2010 - 2013 SlamData, Inc.
 * |_|     |_| \_\ |_____|  \____|   /__/   \____|        All Rights Reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the 
 * GNU Affero General Public License as published by the Free Software Foundation, either version 
 * 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; 
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See 
 * the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this 
 * program. If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.precog.daze

import scala.util.Random

import com.precog.common._
import com.precog.yggdrasil._

import com.precog.common.Path
import com.precog.common.json._
import com.precog.util.IOUtils

import org.specs2.mutable._

import java.io.File

import scalaz._

trait LinearRegressionTestSupport[M[+_]]
    extends StdLibEvaluatorStack[M]
    with RegressionTestSupport[M] {

  import library._
  import dag._
  import instructions._

  def predictionInput(morph: Morphism2, modelData: String, model: String) = {
    val line = Line(0, 0, "")
    dag.Morph2(morph,
      dag.LoadLocal(Const(CString(modelData))(line))(line),
      dag.LoadLocal(Const(CString(model))(line))(line)
    )(line)
  }

  def createLinearSamplePoints(length: Int, noSamples: Int, actualThetas: Array[Double]): Seq[(Array[Double], Double)] = {
    val testSeqX = {
      def createXs: Array[Double] = {
        Seq.fill(length - 1)(Random.nextDouble) map {
          x => x * 2.0 - 1.0
        } toArray
      }

      Seq.fill(noSamples)(createXs)
    }

    val deciders = Seq.fill(noSamples)(Random.nextDouble)

    val testSeqY = {
      testSeqX map { 
        case xs => {
          val yvalue = dotProduct(actualThetas, 1.0 +: xs)
          yvalue + Random.nextGaussian
        }
      }
    }
  
    testSeqX zip testSeqY
  }
}

trait LinearRegressionSpecs[M[+_]] extends Specification 
    with EvaluatorTestSupport[M]
    with LinearRegressionTestSupport[M]
    with LongIdMemoryDatasetConsumer[M] { self =>

  import dag._
  import instructions._
  import library._

  def testAPIKey = "testAPIKey"

  def testEval(graph: DepGraph): Set[SEvent] = {
    consumeEval(testAPIKey, graph, Path.Root) match {
      case Success(results) => results
      case Failure(error) => throw error
    }
  }

  def makeDAG(points: String) = {
    val line = Line(1, 1, "")

    dag.Morph2(MultiLinearRegression,
      dag.Join(DerefArray, CrossLeftSort,
        dag.LoadLocal(Const(CString(points))(line))(line),
        dag.Const(CLong(1))(line))(line),
      dag.Join(DerefArray, CrossLeftSort,
        dag.LoadLocal(Const(CString(points))(line))(line),
        dag.Const(CLong(0))(line))(line))(line)
  }

  def produceResult(cpaths: Seq[CPath], num: Int, actualThetas: Array[Double]) = {
    val samples = createLinearSamplePoints(num, 100, actualThetas)

    val points = jvalues(samples, cpaths) map { _.renderCompact }

    val suffix = ".json"
    val tmpFile = File.createTempFile("values", suffix)
    IOUtils.writeSeqToFile(points, tmpFile).unsafePerformIO

    val pointsString0 = "filesystem" + tmpFile.toString
    val pointsString = pointsString0.take(pointsString0.length - suffix.length)

    val input = makeDAG(pointsString)

    val result = testEval(input)
    tmpFile.delete()

    result
  }

  def returnValues(obj: Map[String, SValue]) = {
    obj.keys mustEqual Set("coefficient", "standard error")
    (obj("coefficient"), obj("standard error"))
  }

  def testTrivial = {
    val num = 2
    val loops = 100

    val actualThetas = makeThetas(num)

    var thetas = List.empty[List[Double]]
    var errors = List.empty[List[Double]]

    var i = 0

    //runs the linear regression function on `loops` sets of data generated from the same distribution
    while (i < loops) {
      val cpaths = Seq(
        CPath(CPathIndex(0), CPathIndex(0)),
        CPath(CPathIndex(1))) sorted

      val result = produceResult(cpaths, num, actualThetas)

      val collection = result collect {
        case (ids, SObject(elems)) if ids.length == 0 =>
          elems.keys mustEqual Set("Model1")

          val SArray(arr) = elems("Model1")

          val (SDecimal(theta1), SDecimal(error1)) = ((arr(0): @unchecked): @unchecked) match {
            case SArray(Vector(SObject(obj))) => returnValues(obj)
          }

          val (SDecimal(theta0), SDecimal(error0)) = ((arr(1): @unchecked): @unchecked) match {
            case SObject(obj) => returnValues(obj)
          }
              
          (List(theta0.toDouble, theta1.toDouble), List(error0.toDouble, error1.toDouble))
      }

      thetas = thetas ++ List(collection.head._1)
      errors = errors ++ List(collection.head._2)
      i += 1
    }

    val combinedThetas = combineResults(num, thetas)
    val combinedErrors = combineResults(num, errors)

    val allThetas = actualThetas zip combinedThetas
    val okThetas = allThetas map { case (t, ts) => isOk(t, ts) }

    val actualErrors = combinedThetas map { t => madMedian(t)._1 }

    val allErrors = actualErrors zip combinedErrors

    val okErrors = allErrors map { case (e, es) => isOk(e, es) } toArray

    okThetas mustEqual Array.fill(num)(true)
    okErrors mustEqual Array.fill(num)(true)
  }

  def testThreeFeatures = {
    val num = 4
    val loops = 100

    val actualThetas = makeThetas(num)

    var thetas = List.empty[List[Double]]
    var errors = List.empty[List[Double]]

    var i = 0

    //runs the linear regression function on `loops` sets of data generated from the same distribution
    while (i < loops) {
      val cpaths = Seq(
        CPath(CPathIndex(0), CPathField("foo")),
        CPath(CPathIndex(0), CPathField("bar")),
        CPath(CPathIndex(0), CPathField("baz")),
        CPath(CPathIndex(1))) sorted

      val result = produceResult(cpaths, num, actualThetas)

      val collection = result collect {
        case (ids, SObject(elems)) if ids.length == 0 =>
          elems.keys mustEqual Set("Model1")

          val SArray(arr) = elems("Model1")

          val (SDecimal(theta1), SDecimal(error1)) = (arr(0): @unchecked) match { case SObject(map) =>
            (map("bar"): @unchecked) match { case SObject(obj) =>
              returnValues(obj)
            }
          }

          val (SDecimal(theta2), SDecimal(error2)) = (arr(0): @unchecked) match { case SObject(map) =>
            (map("baz"): @unchecked) match { case SObject(obj) =>
              returnValues(obj)
            }
          }

          val (SDecimal(theta3), SDecimal(error3)) = (arr(0): @unchecked) match { case SObject(map) =>
            (map("foo"): @unchecked) match { case SObject(obj) =>
              returnValues(obj)
            }
          }

          val (SDecimal(theta0), SDecimal(error0)) = (arr(1): @unchecked) match { case SObject(obj) =>
            returnValues(obj)
          }

          (List(theta0.toDouble, theta1.toDouble, theta2.toDouble, theta3.toDouble),
            List(error0.toDouble, error1.toDouble, error2.toDouble, error3.toDouble))
      }

      thetas = thetas ++ List(collection.head._1)
      errors = errors ++ List(collection.head._2)
      i += 1
    }

    val combinedThetas = combineResults(num, thetas)
    val combinedErrors = combineResults(num, errors)

    val allThetas = actualThetas zip combineResults(num, thetas)
    val okThetas = allThetas map { case (t, ts) => isOk(t, ts) }

    val actualErrors = combinedThetas map { t => madMedian(t)._1 }

    val allErrors = actualErrors zip combinedErrors
    val okErrors = allErrors map { case (e, es) => isOk(e, es) } toArray

    okThetas mustEqual Array.fill(num)(true)
    okErrors mustEqual Array.fill(num)(true)
  }

  def testThreeSchemata = {
    val num = 3
    val loops = 100

    val actualThetas = makeThetas(num)

    var thetasSchema1 = List.empty[List[Double]]
    var thetasSchema2 = List.empty[List[Double]]
    var thetasSchema3 = List.empty[List[Double]]

    var errorsSchema1 = List.empty[List[Double]]
    var errorsSchema2 = List.empty[List[Double]]
    var errorsSchema3 = List.empty[List[Double]]

    var i = 0

    //runs the linear regression function on `loops` sets of data generated from the same distribution
    while (i < loops) {
      val cpaths = Seq(
        CPath(CPathIndex(0), CPathField("ack"), CPathIndex(0)),
        CPath(CPathIndex(0), CPathField("bak"), CPathField("bazoo")),
        CPath(CPathIndex(0), CPathField("bar"), CPathField("baz"), CPathIndex(0)),
        CPath(CPathIndex(0), CPathField("foo")),
        CPath(CPathIndex(1))) sorted

      val samples = {
        val samples0 = createLinearSamplePoints(num, 100, actualThetas)
        samples0 map { case (xs, y) => (Random.nextGaussian +: Random.nextGaussian +: xs, y) }
      }
      val points = jvalues(samples, cpaths, num) map { _.renderCompact }

      val suffix = ".json"
      val tmpFile = File.createTempFile("values", suffix)
      IOUtils.writeSeqToFile(points, tmpFile).unsafePerformIO

      val pointsString0 = "filesystem" + tmpFile.toString
      val pointsString = pointsString0.take(pointsString0.length - suffix.length)
      
      val input = makeDAG(pointsString) 

      val result = testEval(input)
      tmpFile.delete()

      result must haveSize(1)

      def theta(model: String) = result collect {
        case (ids, SObject(elems)) if ids.length == 0 =>
          elems.keys mustEqual Set("Model1", "Model2", "Model3")

          val SArray(arr) = elems(model)

          val (SDecimal(theta1), SDecimal(error1)) = (arr(0): @unchecked) match { case SObject(map) => 
            (map("bar"): @unchecked) match { case SObject(map) => 
              (map("baz"): @unchecked) match { case SArray(Vector(SObject(obj))) =>
                returnValues(obj)
              }
            }
          }

          val (SDecimal(theta2), SDecimal(error2)) = (arr(0): @unchecked) match { case SObject(map) =>
            (map("foo"): @unchecked) match { case SObject(obj) =>
              returnValues(obj)
            }
          }

          val (SDecimal(theta0), SDecimal(error0)) = (arr(1): @unchecked) match {
            case SObject(obj) =>
              returnValues(obj)
          }

          (List(theta0.toDouble, theta1.toDouble, theta2.toDouble), 
            List(error0.toDouble, error1.toDouble, error2.toDouble))
      }

      thetasSchema1 = thetasSchema1 ++ List(theta("Model1").head._1)
      thetasSchema2 = thetasSchema2 ++ List(theta("Model2").head._1)
      thetasSchema3 = thetasSchema3 ++ List(theta("Model3").head._1)

      errorsSchema1 = errorsSchema1 ++ List(theta("Model1").head._2)
      errorsSchema2 = errorsSchema2 ++ List(theta("Model2").head._2)
      errorsSchema3 = errorsSchema3 ++ List(theta("Model3").head._2)

      i += 1
    }

    def getBooleans(actuals: List[Double], values: List[List[Double]]): Array[Boolean] = {
      val zipped = actuals zip combineResults(num, values)
      zipped map { case (t, ts) => isOk(t, ts) } toArray
    }

    val thetas = List(thetasSchema1, thetasSchema2, thetasSchema3)
    val errors = List(errorsSchema1, errorsSchema2, errorsSchema3)

    val resultThetas: List[Array[Boolean]] = thetas map { ts => getBooleans(actualThetas.toList, ts) }

    val actualErrors: List[Seq[Double]] = thetas map { ts => combineResults(num, ts) map { arr => madMedian(arr)._1 } }


    val zipped = actualErrors zip errors

    val resultErrors = zipped map { case (actual, err) =>
      val z = actual zip combineResults(num, err) 
      z map { case (e, es) => isOk(e, es) }
    }

    val expected = Array.fill(num)(true)

    resultThetas(0) mustEqual expected
    resultThetas(1) mustEqual expected
    resultThetas(2) mustEqual expected

    resultErrors(0).toArray mustEqual expected
    resultErrors(1).toArray mustEqual expected
    resultErrors(2).toArray mustEqual expected
  }

  "linear regression" should {
    "pass randomly generated test with a single feature" in (testTrivial or testTrivial)
    "pass randomly generated test with three features inside an object" in (testThreeFeatures or testThreeFeatures)
    "pass randomly generated test with three distinct schemata" in (testThreeSchemata or testThreeSchemata)
  }

  "linear prediction" should {
    "predict simple case" in {

      val input = predictionInput(LinearPrediction, "/hom/model1data", "/hom/model1")

      val result0 = testEval(input)

      result0 must haveSize(19)

      val result = result0 collect { case (ids, value) if ids.size == 2 => value }

      result mustEqual Set(
        (SObject(Map("Model2" -> SDecimal(42.5), "Model1" -> SDecimal(48.5)))),
        (SObject(Map("Model2" -> SDecimal(41.0)))),
        (SObject(Map("Model2" -> SDecimal(8.0), "Model1" -> SDecimal(12.0)))), 
        (SObject(Map("Model2" -> SDecimal(17.0), "Model1" -> SDecimal(6.6)))), 
        (SObject(Map("Model2" -> SDecimal(26.0), "Model1" -> SDecimal(24.0)))), 
        (SObject(Map("Model2" -> SDecimal(23.0), "Model1" -> SDecimal(35.0)))), 
        (SObject(Map("Model2" -> SDecimal(29.0), "Model1" -> SDecimal(39.0)))), 
        (SObject(Map("Model2" -> SDecimal(2.0), "Model1" -> SDecimal(0.0)))), 
        (SObject(Map("Model2" -> SDecimal(-16.0), "Model1" -> SDecimal(-18.0)))), 
        (SObject(Map("Model3" -> SDecimal(9.5)))), 
        (SObject(Map("Model3" -> SDecimal(7.0)))), 
        (SObject(Map("Model3" -> SDecimal(-11.0)))), 
        (SObject(Map("Model3" -> SDecimal(19.75)))), 
        (SObject(Map("Model3" -> SDecimal(-0.5)))), 
        (SObject(Map("Model3" -> SDecimal(17.0)))), 
        (SObject(Map("Model3" -> SDecimal(14.5)))), 
        (SObject(Map("Model3" -> SDecimal(-0.5)))), 
        (SObject(Map("Model3" -> SDecimal(24.5)))), 
        (SObject(Map("Model3" -> SDecimal(-0.5)))))
    }

    "predict case with repeated model names and arrays" in {
      val input = predictionInput(LinearPrediction, "/hom/model2data", "/hom/model2")

      val result0 = testEval(input)

      result0 must haveSize(14)

      val result = result0 collect { case (ids, value) if ids.size == 2 => value }

      result mustEqual Set(
        (SObject(Map("Model1" -> SDecimal(8.0), "Model3" -> SDecimal(18.0)))), 
        (SObject(Map("Model1" -> SDecimal(17.0)))), 
        (SObject(Map("Model1" -> SDecimal(23.0)))),
        (SObject(Map("Model1" -> SDecimal(2.0)))), 
        (SObject(Map("Model2" -> SDecimal(14.0), "Model1" -> SDecimal(9.0)))), 
        (SObject(Map("Model1" -> SDecimal(18.0)))), 
        (SObject(Map("Model1" -> SDecimal(24.0)))),
        (SObject(Map("Model1" -> SDecimal(3.0)))), 
        (SObject(Map("Model3" -> SDecimal(0.0)))),
        (SObject(Map("Model3" -> SDecimal(7.2)))),
        (SObject(Map("Model3" -> SDecimal(-5.1)))), 
        (SObject(Map("Model2" -> SDecimal(36.0)))),
        (SObject(Map("Model3" -> SDecimal(-4.0)))),
        (SObject(Map("Model2" -> SDecimal(77.0)))))
    }
  }
}

object LinearRegressionSpecs extends LinearRegressionSpecs[test.YId] with test.YIdInstances