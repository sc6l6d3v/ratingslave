package com.iscs.ratingbunny.util

import cats.effect.kernel.Sync

import scala.util.Try

trait DecodeUtils {
  def getRating[F[_]: Sync](rating: String): F[Double] =
    Sync[F].delay(Try(rating.toDouble).toOption.getOrElse(5.0d))

  def protoc(imageHost: String): String =
    if (
      List("tmdbimg", "localhost", "192.168.4", "172")
        .exists(host => imageHost.startsWith(host))
    )
      "http"
    else
      "https"
}
