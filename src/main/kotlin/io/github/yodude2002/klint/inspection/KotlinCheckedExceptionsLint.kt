package io.github.yodude2002.klint.inspection

import com.intellij.codeInspection.ProblemsHolder
import org.jetbrains.kotlin.idea.codeinsight.api.classic.inspections.AbstractKotlinInspection
import org.jetbrains.kotlin.idea.structuralsearch.resolveDeclType
import org.jetbrains.kotlin.idea.structuralsearch.resolveExprType
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.referenceExpression
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlinx.serialization.compiler.backend.common.serialName

class KotlinCheckedExceptionsLint : AbstractKotlinInspection() {

    // TODO: subclassing exceptions
    // TODO: quick fix
    override fun buildVisitor(
            holder: ProblemsHolder,
            isOnTheFly: Boolean
    ): KtVisitorVoid {
        val recursiveTreeVisitor = object: KtTreeVisitor<StateBundle>() {

            override fun visitTryExpression(expression: KtTryExpression, data: StateBundle): Void? {

                val catch = expression.catchClauses
                    .mapNotNull { it.catchParameter }
                    .mapNotNull { it.resolveDeclType() }

                val newState = data
                        .appendClasses(catch)
                        .withCatch(expression)

                visitBlockExpression(expression.tryBlock, newState)

                for (catchClause in expression.catchClauses) {
                    catchClause.catchBody?.let {
                        visitExpression(it, data)
                    }
                }

                expression.finallyBlock?.let {
                    visitFinallySection(it, StateBundle())
                }

                return null
            }

            override fun visitNamedFunction(function: KtNamedFunction, data: StateBundle): Void? {
                // Intentionally prevent recursion here
                return null
            }

            override fun visitCallExpression(expr: KtCallExpression, data: StateBundle): Void? {
                super.visitCallExpression(expr, data)

                val refExpr = expr.referenceExpression() ?: return null

                val javaThrows = expr.getJavaCaller()?.throwsTypes()
                val kotlinThrows = expr.getKotlinCaller()?.annotatedThrows()

                if (javaThrows != null) {
                    val unhandledThrows = javaThrows.filter { pc ->
                        data.classes.none { it.serialName() == pc.qualifiedName }
                    }
                    for (unhandledThrow in unhandledThrows) {
                        holder.registerProblem(refExpr,
                            "Java: Unhandled exception: ${unhandledThrow.qualifiedName}"
                        )
                    }
                } else if (kotlinThrows != null) {
                    val unhandledThrows = kotlinThrows.filter { pc ->
                        data.classes.none { it.serialName() == pc.serialName() }
                    }
                    for (unhandledThrow in unhandledThrows) {
                        holder.registerProblem(refExpr,
                            "Kotlin: Unhandled exception: ${unhandledThrow.serialName()}"
                        )
                    }
                }

                return null
            }

            override fun visitThrowExpression(expression: KtThrowExpression, data: StateBundle): Void? {
                super.visitThrowExpression(expression, data)

                val throwExpr = expression.thrownExpression ?: return null
                val throwType = throwExpr.resolveExprType() ?: return null

                if (data.classes.none { it.serialName() == throwType.serialName() }) {
                    holder.registerProblem(
                        expression,
                        "Throw: Unhandled exception: ${throwType.serialName()}"
                    )
                }

                return null
            }
        }

        return namedFunctionVisitor { nf ->
            val bodyExpr = nf.bodyBlockExpression ?: return@namedFunctionVisitor

            val annotatedClasses = nf.annotatedThrows()

            val state = StateBundle(annotatedClasses, nf)

            recursiveTreeVisitor.visitBlockExpression(bodyExpr, state)
        }
    }

    private data class StateBundle(
        val classes: List<KotlinType> = listOf(),
        val function: KtNamedFunction? = null,
        val catch: KtTryExpression? = null,
    ) {
        constructor(function: KtNamedFunction): this(listOf(), function)

        fun appendClasses(classes: List<KotlinType>): StateBundle {
            return StateBundle(listOf(classes, this.classes).flatten(), this.function, this.catch)
        }

        fun withCatch(catch: KtTryExpression): StateBundle {
            return StateBundle(this.classes, this.function, catch)
        }
    }

}

