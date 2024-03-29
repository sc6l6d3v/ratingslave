package com.iscs.ratingslave

import cats.effect.{Async, Resource, Sync}
import cats.implicits._
import com.comcast.ip4s._
import com.iscs.ratingslave.codecs.CustomCodecs
import com.iscs.ratingslave.domains.ImdbQuery.{AutoNameRec, TitleRec}
import com.iscs.ratingslave.domains.{EmailContact, ImdbQuery, ReleaseDates}
import com.iscs.ratingslave.routes.{EmailContactRoutes, ImdbRoutes, ReleaseRoutes}
import com.typesafe.scalalogging.Logger
import io.circe.generic.auto._
import mongo4cats.circe._
import mongo4cats.database.MongoDatabase
import org.http4s.HttpApp
import org.http4s.client.Client
import org.http4s.ember.server._
import org.http4s.implicits._
import org.http4s.server.middleware.{Logger => hpLogger}
import org.http4s.server.{Router, Server}

import java.util.concurrent.Executors
import scala.concurrent.{ExecutionContext, ExecutionContextExecutorService}

object Server extends CustomCodecs {
  private val L = Logger[this.type]

  private val port = sys.env.getOrElse("PORT", "8080").toInt
  private val bindHost = sys.env.getOrElse("BINDHOST", "0.0.0.0")
  private val serverPoolSize = sys.env.getOrElse("SERVERPOOL", "16").toInt
  private val defaultHost = sys.env.getOrElse("DATASOURCE", "www.dummy.com")
  private val imageHost = sys.env.getOrElse("IMAGESOURCE", "localhost:8083")

  private val nameCollection = "name_basics"
  private val titleCollection = "title_basics_ratings"
  private val titlePrincipalsCollection = "title_principals_withname"

  private val emailCollection = "email_contact"

  def getPool[F[_] : Sync](size: Int): F[ExecutionContextExecutorService] = for {
    es <- Sync[F].delay(Executors.newFixedThreadPool(size))
    ex <- Sync[F].delay(ExecutionContext.fromExecutorService(es))
  } yield ex

  private def getImdbSvc[F[_]: Async](db: MongoDatabase[F]): F[ImdbQuery[F]] =  for {
    titleCollCodec <- db.getCollectionWithCodec[TitleRec](titleCollection)
    titlePrincipleCollCodec <- db.getCollectionWithCodec[TitleRec](titlePrincipalsCollection)
    nameCollCodec <- db.getCollectionWithCodec[AutoNameRec](nameCollection)
    imdbSvc <- Sync[F].delay(ImdbQuery.impl[F](titleCollCodec,
      titlePrincipleCollCodec,
      nameCollCodec))
  } yield imdbSvc

  private def getEmailSvc[F[_]: Async](db: MongoDatabase[F]): F[EmailContact[F]] = for {
    emailColl <- db.getCollection(emailCollection)
    emailSvc <- Sync[F].delay(EmailContact.impl[F](emailColl))
  } yield emailSvc

  def getServices[F[_]: Async](db: MongoDatabase[F], client: Client[F]): F[HttpApp[F]] = {
    for {
      imdbSvc <- getImdbSvc(db)
      emailSvc <- getEmailSvc(db)
      scrapeSvc <- Sync[F].delay(new ReleaseDates[F](defaultHost, imageHost, client))
      httpApp <- Sync[F].delay(
        Router("/" ->
          (ReleaseRoutes.httpRoutes(scrapeSvc) <+>
            EmailContactRoutes.httpRoutes(emailSvc) <+>
            ImdbRoutes.httpRoutes(imdbSvc))
        )
          .orNotFound)
      _ <- Sync[F].delay(L.info(s""""added routes for reldate"""))
      finalHttpApp <- Sync[F].delay(hpLogger.httpApp(logHeaders = true, logBody = false)(httpApp))
    } yield finalHttpApp
  }

  def getResource[F[_]: Async](finalHttpApp: HttpApp[F]): Resource[F, Server] = {
    for {
      server <- EmberServerBuilder
        .default[F]
        .withHost(Ipv4Address.fromString(bindHost).getOrElse(ipv4"0.0.0.0"))
        .withPort(Port.fromInt(port).getOrElse(port"8080"))
        .withHttpApp(finalHttpApp)
        .withMaxConnections(serverPoolSize)
        .build
    } yield server
  }
}