/*
 * Copyright 2001-2013 Artima, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.scalautils
import reflect.macros.Context

trait MacroExpr[T] {
  val value: T
}

object MacroExpr {

  import scala.language.experimental.macros

  def applyExpr[T](value: T, qualifier: Any, name: String, decodedName: String, args: List[Any]): MacroExpr[T] =
    ApplyMacroExpr(value, qualifier, name, decodedName, args)

  def selectExpr[T](value: T, qualifier: Any, name: String, decodedName: String): MacroExpr[T] =
    SelectMacroExpr(value, qualifier, name, decodedName)

  def identExpr[T](value: T, name: String): MacroExpr[T] =
    IdentMacroExpr(value, name)

  def thisExpr[T](value: T): MacroExpr[T] =
    ThisExpr(value)

  def fallbackExpr[T](value: T, expressionText: String): MacroExpr[T] =
    FallbackExpr(value, expressionText)

  def expression[T](expr: T): MacroExpr[T] = macro MacroExpr.buildExpression[T]

  def buildExpression[T](context: Context)(expr: context.Expr[T]): context.Expr[MacroExpr[T]] = {
    import context.universe._

    def valDef(name: String, rhs: Tree): ValDef =
      ValDef(
        Modifiers(),
        newTermName(name),
        TypeTree(),
        rhs
      )

    def fallback(tree: Tree): Tree =
      Apply(
        Select(
          Select(
            Select(
              Ident(newTermName("org")),
              newTermName("scalautils")
            ),
            newTermName("MacroExpr")
          ),
          newTermName("fallbackExpr")
        ),
        List(
          tree,
          context.literal(show(tree)).tree
        )
      )

    def transformAst(tree: Tree): Tree =
       tree match {
        case apply: Apply =>
          apply.fun match {
            case select: Select =>
              val funValDef =
                valDef(
                  "$org_scalautils_macro_apply_qualifier",
                  transformAst(select.qualifier)
                )
              val argsValDef =
                apply.args.zipWithIndex.map { case (arg, idx) =>
                  valDef("$org_scalautils_macro_apply_arg_" + idx, transformAst(arg))  // TODO: probably should traverse arg also
                }
              val argsNames = argsValDef.map(vd => Ident(vd.name))
              val newExpr =
                Apply(
                  Select(
                    Select(
                      Ident(newTermName("$org_scalautils_macro_apply_qualifier")),
                      newTermName("value")
                    ),
                    select.name
                  ),
                  argsNames.map(arg => Select(arg, newTermName("value")))
                )

              val applyExprCall =
                Apply(
                  Select(
                    Select(
                      Select(
                        Ident(newTermName("org")),
                        newTermName("scalautils")
                      ),
                      newTermName("MacroExpr")
                    ),
                    newTermName("applyExpr")
                  ),
                  List(
                    newExpr,
                    Ident(newTermName("$org_scalautils_macro_apply_qualifier")),
                    context.literal(select.name.toString).tree,
                    context.literal(select.name.decoded).tree,
                    Apply(
                      Select(
                        Select(
                          Select(
                            Select(
                              Ident(newTermName("scala")),
                              newTermName("collection")
                            ),
                            newTermName("immutable")
                          ),
                          newTermName("List")
                        ),
                        newTermName("apply")
                      ),
                      argsNames
                    )
                  )
                )
              val codeInBlock: List[Tree] = List(funValDef) ++ argsValDef ++ List(applyExprCall)
              Block(codeInBlock: _*)

            case _ => fallback(tree) // TODO: Not sure what to do here
          }
        case select: Select =>
          val qualifierValDef =
            valDef(
              "$org_scalautils_macro_apply_qualifier",
              transformAst(select.qualifier)
            )
          val newExpr =
            Select(
              Select(
                Ident(newTermName("$org_scalautils_macro_apply_qualifier")),
                newTermName("value")
              ),
              select.name
            )
          Block(
            qualifierValDef,
            Apply(
              Select(
                Select(
                  Select(
                    Ident(newTermName("org")),
                    newTermName("scalautils")
                  ),
                  newTermName("MacroExpr")
                ),
                newTermName("selectExpr")
              ),
              List(
                newExpr,
                Ident(newTermName("$org_scalautils_macro_apply_qualifier")),
                context.literal(select.name.toString).tree,
                context.literal(select.name.decoded).tree
              )
            )
          )

        case ident: Ident => fallback(ident.duplicate)
        case thisTree: This =>
          Apply(
            Select(
              Select(
                Select(
                  Ident(newTermName("org")),
                  newTermName("scalautils")
                ),
                newTermName("MacroExpr")
              ),
              newTermName("thisExpr")
            ),
            List(
              tree
            )
          )
        case _ => fallback(tree)
      }

    val block = transformAst(expr.tree)
    context.Expr(block)
  }

}

private[scalautils] case class ApplyMacroExpr[T](value: T, qualifier: Any, name: String, decodedName: String, args: List[Any]) extends MacroExpr[T] {

  private def notNested(expr: Any): Boolean =
    expr match {
      case apply: ApplyMacroExpr[_] => false
      case _ => true
    }

  private val symbolicSet = Set("*", "/", "%", "+", "-", ":", "=", "!", "<", ">", "&", "^", "|")

  private lazy val isSymbolic: Boolean = symbolicSet.exists(e => decodedName.startsWith(e))

  private lazy val isSingleNotNested: Boolean = args.length == 1 && notNested(args(0))

  private def bracketIfNested(expr: Any): String =
    expr match {
      case apply: ApplyMacroExpr[_] => "(" + Prettifier.default(expr) + ")"
      case _ => Prettifier.default(expr)
    }

  override def toString: String =
    if (isSymbolic) {
      if (isSingleNotNested)
        bracketIfNested(qualifier) + " " + decodedName + " " + args.map(Prettifier.default(_)).mkString(", ")
      else
        bracketIfNested(qualifier) + " " + decodedName + " (" + args.map(Prettifier.default(_)).mkString(", ") + ")"
    }
    else
      Prettifier.default(qualifier) + "." + decodedName + "(" + args.map(Prettifier.default(_)).mkString(", ") + ")"
}

private[scalautils] case class SelectMacroExpr[T](value: T, qualifier: Any, name: String, decodedName: String) extends MacroExpr[T] {

  override def toString: String =
    qualifier match {
      case thisExpr: ThisExpr[_] => Prettifier.default(value)
      case _ =>
        if (decodedName.startsWith("unary_"))
          decodedName.substring(6) +  Prettifier.default(qualifier)
        else
          Prettifier.default(qualifier) + "." + decodedName
    }
}

private[scalautils] case class IdentMacroExpr[T](value: T, name: String) extends MacroExpr[T]

private[scalautils] case class ThisExpr[T](value: T) extends MacroExpr[T] {
  override def toString: String = "" // omit this in printing
}

private[scalautils] case class FallbackExpr[T](value: T, expressionText: String) extends MacroExpr[T] {

  override def toString: String = Prettifier.default(value)

}