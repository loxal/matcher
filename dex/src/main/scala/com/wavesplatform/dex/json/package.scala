package com.wavesplatform.dex

import com.wavesplatform.dex.domain.asset.{Asset, AssetPair}
import com.wavesplatform.dex.settings.formatValue
import play.api.libs.json._

package object json {
  // TODO create a function with f
  // create an implementation with formatValue
  val stringAsDoubleFormat: Format[Double] = Format(
    Reads.StringReads.map(_.toDouble),
    Writes.StringWrites.contramap[Double](formatValue(_))
  )

  implicit val assetDoubleMapFormat: Format[Map[Asset, Double]] = assetMapFormat[Double]

  implicit def assetMapFormat[V: Format]: Format[Map[Asset, V]] = mapFormat[Asset, V](
    stringifyKey = _.toString,
    parseKey = x => Asset.fromString(x).fold[JsResult[Asset]](JsError(s"Can't parse '$x' as Asset"))(JsSuccess(_))
  )

  implicit def assetPairMapFormat[V: Format]: Format[Map[AssetPair, V]] = mapFormat[AssetPair, V](
    stringifyKey = _.key,
    parseKey = x => {
      val assetPairStrArr = x.split("-")
      assetPairStrArr match {
        case Array(amtAssetStr, prcAssetStr) =>
          AssetPair
            .createAssetPair(amtAssetStr, prcAssetStr)
            .fold(e => JsError(s"Can't parse '$x' as AssetPair: ${e.getMessage}"), JsSuccess(_))
        case _ => JsError(s"$x (incorrect assets count, expected 2 but got ${assetPairStrArr.size})")
      }
    }
  )

  def mapFormat[K, V: Format](stringifyKey: K => String, parseKey: String => JsResult[K]): Format[Map[K, V]] = {
    val vReads = implicitly[Reads[V]]
    Format(
      fjs = Reads {
        case JsObject(xs) =>
          xs.foldLeft[JsResult[Map[K, V]]](JsSuccess(Map.empty[K, V])) {
            case (r, (k, v)) =>
              for {
                r <- r
                k <- parseKey(k)
                v <- vReads.reads(v)
              } yield r.updated(k, v)
          }
        case x => JsError(s"Can't parse map: $x")
      },
      tjs = Writes.map[V].contramap(_.map { case (k, v) => stringifyKey(k) -> v })
    )
  }
}
