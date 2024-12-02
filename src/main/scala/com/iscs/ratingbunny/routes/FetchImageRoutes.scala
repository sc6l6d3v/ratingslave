package com.iscs.ratingbunny.routes

import cats.effect.{Async, Sync}
import cats.implicits.*
import com.iscs.ratingbunny.domains.FetchImage
import com.iscs.ratingbunny.util.DecodeUtils
import com.typesafe.scalalogging.Logger
import org.http4s.*
import org.http4s.MediaType.image.*
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.`Content-Type`

object FetchImageRoutes {
  private val L          = Logger[this.type]
  private val apiVersion = "v3"

  def httpRoutes[F[_]: Async](R: FetchImage[F]): HttpRoutes[F] = {
    val dsl = Http4sDsl[F]
    import dsl.*
    val imgSvc = HttpRoutes
      .of[F] { case _ @GET -> Root / "api" / `apiVersion` / "image" / imdbId =>
        for {
          _    <- Sync[F].delay(L.info(s""""request" image=$imdbId"""))
          resp <- Ok(R.getImage(imdbId))
        } yield resp
      }
      .map(_.withContentType(`Content-Type`(`jpeg`)))
    CORSSetup.methodConfig(imgSvc)
  }
}