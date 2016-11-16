package com.twitter.finagle.postgres.generic

import java.nio.charset.Charset

import com.twitter.finagle.postgres.messages.SelectResult
import com.twitter.finagle.postgres._
import com.twitter.finagle.postgres.values.ValueDecoder
import com.twitter.util.{Await, Future}
import org.scalamock.scalatest.MockFactory
import org.scalatest.{FreeSpec, Matchers}

class QuerySpec extends FreeSpec with Matchers with MockFactory {


  val row = mock[Row]

  trait MockClient {
    def prepareAndQuery[T](sql: String, params: List[Param[_]], f: Row => T): Future[Seq[T]]
    def prepareAndExecute(sql: String, params: List[Param[_]]): Future[Int]
  }

  val mockClient = mock[MockClient]

  val client = new PostgresClient {

    def prepareAndQuery[T](sql: String, params: Param[_]*)(f: (Row) => T): Future[Seq[T]] =
      mockClient.prepareAndQuery(sql, params.toList, f)

    def prepareAndExecute(sql: String, params: Param[_]*): Future[Int] =
      mockClient.prepareAndExecute(sql, params.toList)

    def fetch(sql: String): Future[SelectResult] = ???

    def execute(sql: String): Future[OK] = ???

    def charset: Charset = ???

    def select[T](sql: String)(f: (Row) => T): Future[Seq[T]] = ???

    def close(): Future[Unit] = ???

    def executeUpdate(sql: String): Future[OK] = ???

    def inTransaction[T](fn: (PostgresClient) => Future[T]): Future[T] = ???

    def query(sql: String): Future[QueryResponse] = ???

  }

  def expectQuery[U](expectedQuery: String, expectedParams: Param[_]*)(query: Query[U]) = {
    mockClient.prepareAndQuery[U] _ expects (expectedQuery, expectedParams.toList, *) onCall {
      (q, p, fn) => Future.value(Seq(fn(row)))
    }
    Await.result(query.run(client)).head
  }

  "Query" - {

    "enriches StringContext" in {
      expectQuery("SELECT * FROM foo") {
        sql"SELECT * FROM foo"
      } shouldEqual row
    }

    "parameterizes" - {
      "with param in terminal position" in {

        val p1 = "foo"
        val p2 = 22

        expectQuery("SELECT * FROM foo WHERE p1 = $1 AND p2 = $2", p1, p2) {
          sql"SELECT * FROM foo WHERE p1 = $p1 AND p2 = $p2"
        } shouldEqual row
      }

      "with no param in terminal position" in {
        val p1 = "foo"
        val p2 = 22

        expectQuery("SELECT * FROM foo WHERE p1 = $1 AND p2 = $2 ORDER BY p1", p1, p2) {
          sql"SELECT * FROM foo WHERE p1 = $p1 AND p2 = $p2 ORDER BY p1"
        } shouldEqual row
      }

      "tuple params for IN operator" in {
        val p1 = ("foo", "bar")
        expectQuery("SELECT * FROM foo WHERE a IN ($1, $2)", "foo", "bar") {
          sql"SELECT * FROM foo WHERE a IN ($p1)"
        }
      }

      "list params for IN operator" in {
        val p1 = List("foo", "bar")
        expectQuery("SELECT * FROM foo WHERE a IN ($1, $2)", "foo", "bar") {
          sql"SELECT * FROM foo WHERE a IN ($p1)"
        }
      }
    }

    "map" in {
      val p1 = "foo"
      val p2 = 22

      (row.getOption[Int](_:String)(_: ValueDecoder[Int])) expects ("bar", *) returning Some(2)

      expectQuery("SELECT * FROM foo WHERE p1 = $1 AND p2 = $2 ORDER BY p1", p1, p2) {
        sql"SELECT * FROM foo WHERE p1 = $p1 AND p2 = $p2 ORDER BY p1".map {
          row => row.getOption[Int]("bar")
        }
      } shouldEqual Some(2)
    }

    "as" - {
      "all scalars" in {

        case class Foo(i: Int, s: String)

        val p1 = "foo"
        val p2 = 22

        (row.get[Int](_: String)(_: ValueDecoder[Int])) expects("i", *) returning 2
        (row.get[String](_: String)(_: ValueDecoder[String])) expects("s", *) returning "two"

        expectQuery("SELECT * FROM foo WHERE p1 = $1 AND p2 = $2 ORDER BY p1", p1, p2) {
          sql"SELECT * FROM foo WHERE p1 = $p1 AND p2 = $p2 ORDER BY p1".as[Foo]
        } shouldEqual Foo(2, "two")
      }

      "with options" in {
        case class Foo(i: Int, s: Option[String])
        val p1 = "foo"
        val p2 = 22

        (row.get[Int](_: String)(_: ValueDecoder[Int])) expects("i", *) returning 2
        (row.getOption[String](_: String)(_: ValueDecoder[String])) expects("s", *) returning Some("two")

        expectQuery("SELECT * FROM foo WHERE p1 = $1 AND p2 = $2 ORDER BY p1", p1, p2) {
          sql"SELECT * FROM foo WHERE p1 = $p1 AND p2 = $p2 ORDER BY p1".as[Foo]
        } shouldEqual Foo(2, Some("two"))

        (row.get[Int](_: String)(_: ValueDecoder[Int])) expects("i", *) returning 2
        (row.getOption[String](_: String)(_: ValueDecoder[String])) expects("s", *) returning None

        expectQuery("SELECT * FROM foo WHERE p1 = $1 AND p2 = $2 ORDER BY p1", p1, p2) {
          sql"SELECT * FROM foo WHERE p1 = $p1 AND p2 = $p2 ORDER BY p1".as[Foo]
        } shouldEqual Foo(2, None)
      }
    }
  }

}
