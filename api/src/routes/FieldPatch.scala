package wifihaven.api.routes

import zio.json.*
import zio.json.ast.Json

// Tri-state PATCH semantics (#996-#999). zio-json's default Option codec
// collapses absent and JSON null, so PATCH handlers parse Json.Obj directly
// to distinguish:
//   - field absent  → Absent       (no change)
//   - field == null → Cleared      (set to NULL, where the column allows)
//   - field == v    → Set(v)       (assign)
enum FieldPatch[+T] {
  case Absent
  case Cleared
  case Set(value: T)

  // Apply to a non-nullable field: only Set wins; Cleared is treated as Absent
  // (handlers should reject Cleared earlier for non-nullable fields).
  def applyTo[U >: T](existing: U): U = this match {
    case Set(v) => v
    case _      => existing
  }

  // Apply to a nullable field: Set assigns, Cleared writes NULL, Absent preserves.
  def applyToNullable[U >: T](existing: Option[U]): Option[U] = this match {
    case Absent  => existing
    case Cleared => None
    case Set(v)  => Some(v)
  }
}

object FieldPatch {
  def from[T](obj: Json.Obj, key: String)(using dec: JsonDecoder[T]): Either[String, FieldPatch[T]] =
    obj.get(key) match {
      case None            => Right(Absent)
      case Some(Json.Null) => Right(Cleared)
      case Some(j)         => dec.fromJsonAST(j).map(Set(_))
    }

  // Parse a PATCH request body string to a Json.Obj, or return a decode error
  // suitable for a 400 response.
  def parseObj(body: String): Either[String, Json.Obj] =
    body.fromJson[Json].flatMap {
      case obj: Json.Obj => Right(obj)
      case _             => Left("expected JSON object")
    }
}
