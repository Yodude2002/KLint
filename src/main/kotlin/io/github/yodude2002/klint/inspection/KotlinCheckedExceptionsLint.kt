package io.github.yodude2002.klint.inspection

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.codeStyle.CodeStyleManager
import org.jetbrains.kotlin.idea.codeinsight.api.classic.inspections.AbstractKotlinInspection
import org.jetbrains.kotlin.idea.core.ShortenReferences
import org.jetbrains.kotlin.idea.core.surroundWith.KotlinSurrounderUtils
import org.jetbrains.kotlin.idea.structuralsearch.resolveDeclType
import org.jetbrains.kotlin.idea.structuralsearch.resolveExprType
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.referenceExpression
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlinx.serialization.compiler.backend.common.serialName

class KotlinCheckedExceptionsLint : AbstractKotlinInspection() {

    // TODO: subclassing exceptions
    // TODO: way to exempt certain lambdas
    // TODO: make intentions available even if don't need fixes
    // TODO: treat RuntimeExceptions better
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

                val javaThrows = expr.getJavaCaller()?.throwsTypes()
                val kotlinThrows = expr.getKotlinCaller()?.annotatedThrows()

                if (javaThrows != null) {
                    val unhandled = javaThrows.mapNotNull { it.qualifiedName }
                        .filter { pc -> data.classes.none { it.serialName() == pc} }
                    if (unhandled.isNotEmpty()) {
                        holder.kceWarning(
                            expr.referenceExpression() ?: expr,
                            unhandled,
                            data
                        )
                    }
                } else if (kotlinThrows != null) {
                    val unhandled = kotlinThrows.map { it.serialName() }
                        .filter { pc -> data.classes.none { it.serialName() == pc} }
                    if (unhandled.isNotEmpty()) {
                        holder.kceWarning(
                            expr.referenceExpression() ?: expr,
                            unhandled,
                            data
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
                    holder.kceWarning(
                        expression,
                        listOf(throwType.serialName()),
                        data
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

    private fun ProblemsHolder.kceWarning(location: KtExpression, qualifiedNames: List<String>, state: StateBundle) {
        val fixes = mutableListOf<LocalQuickFix>()
        if (state.catch != null) {
            fixes.add(AddToTryCatchQuickFix(qualifiedNames))
        }
        if (state.function != null) {
            fixes.add(AddToThrowsQuickFix(qualifiedNames))
        }
        fixes.add(SurroundWithTryCatchQuickFix(qualifiedNames))

        registerProblem(
            location,
            if (qualifiedNames.size == 1) {
                "Unhandled exception: ${qualifiedNames[0]}"
            } else {
                "Unhandled exceptions: ${qualifiedNames.joinToString(separator = ", ")}"
           }, *(fixes.toTypedArray()))
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

    private class AddToThrowsQuickFix(qualifiedNames: List<String>): LocalQuickFix {

        private val classes: Array<String> = qualifiedNames.toTypedArray()

        override fun getFamilyName(): String {
            return "Add 'Throws' annotation"
        }

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {

            val factory = KtPsiFactory(project)
            val styleManager = CodeStyleManager.getInstance(project)

            val function = findParentFunction(descriptor.psiElement as? KtExpression ?: return) ?: return

            val throwsAnnotation = function.annotationEntries
                .filter { it.isThrowsAnnotation() }
                .getOrNull(0)

            if (throwsAnnotation != null) {
                val newList = factory.createAnnotationEntry("@Throws(${
                    classes.joinToString(separator = ", ") {
                        "$it::class"
                    }
                })").valueArgumentList!!
                ShortenReferences.DEFAULT.process(newList)
                throwsAnnotation.valueArgumentList?.let {
                    for (av in newList.arguments) {
                        it.addArgument(av)
                    }
                } ?: run {
                    throwsAnnotation.add(newList)
                }
                styleManager.reformat(throwsAnnotation)
            } else {
                val newAnnotation = function.addAnnotationEntry(factory.createAnnotationEntry("@Throws(${
                    classes.joinToString(separator = ", ") {
                        "$it::class"
                    }
                })"))
                styleManager.reformat(newAnnotation)
                ShortenReferences.DEFAULT.process(newAnnotation)
            }

        }

        fun findParentFunction(inputExpr: KtExpression): KtNamedFunction? {
            var expr = inputExpr
            while(true) {
                expr = when(expr) {
                    is KtNamedFunction -> return expr
                    else -> expr.parent as? KtExpression ?: return null
                }
            }
        }
    }

    private class AddToTryCatchQuickFix(qualifiedNames: List<String>): LocalQuickFix {

        val classes: Array<String> = qualifiedNames.toTypedArray()

        override fun getFamilyName(): String {
            return "Add catch to try expression"
        }

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {

            val factory = KtPsiFactory(project)

            val tryExpr = findTryExpression(descriptor.psiElement as? KtExpression ?: return) ?: return

            val newCatches = classes.map {
                factory.createExpression("try {} catch(e: $it) {\nTODO(\"Not yet implemented\")\n}") as KtTryExpression
            }
                .map { it.catchClauses[0].let { ShortenReferences.DEFAULT.process(it) } }

            val finally = tryExpr.finallyBlock
            if (finally != null) {
                for (newCatch in newCatches) {
                    tryExpr.addBefore(newCatch, finally)
                }
            } else {
                for (newCatch in newCatches) {
                    tryExpr.add(newCatch)
                }
            }
        }

        fun findTryExpression(inputExpr: KtExpression): KtTryExpression? {
            var expr = inputExpr
            while (true) {
                expr = when(expr) {
                    is KtTryExpression -> return expr
                    else -> expr.parent as? KtExpression ?: return null
                }
            }
        }

    }

    private class SurroundWithTryCatchQuickFix(qualifiedNames: List<String>): LocalQuickFix {

        val classes: Array<String> = qualifiedNames.toTypedArray()

        override fun getFamilyName(): String {
            return "Surround with try/catch"
        }

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {

            val throwElement = descriptor.psiElement as? KtThrowExpression

            val element = throwElement
                ?: ((descriptor.psiElement as? KtExpression)?.let { getCallStatement(it) })
                ?: return

            val template = "try { \n}${
                classes.joinToString(separator = "") {
                    " catch(e: $it) {\nTODO(\"Not yet implemented\")\n}"
                }
            }"

            var tryExpr = KtPsiFactory(project).createExpression(template) as KtTryExpression
            val container = element.parent

            tryExpr = container.addAfter(tryExpr, element) as KtTryExpression

            KotlinSurrounderUtils.addStatementsInBlock(tryExpr.tryBlock, arrayOf(element))
            container.deleteChildRange(element, element)

            CodeStyleManager.getInstance(project).reformat(tryExpr)

            ShortenReferences.DEFAULT.process(tryExpr)
        }

        // TODO: make this actually good
        fun getCallStatement(inputExpr: KtExpression): KtExpression {
            var expr = inputExpr
            while (true) {
                val parent = expr.parent
                expr = when(parent) {
                    is KtBreakExpression -> parent
                    is KtCallExpression -> parent
                    is KtContinueExpression -> parent
                    is KtDeclaration -> parent
                    is KtQualifiedExpression -> parent
                    is KtDoubleColonExpression -> parent
                    is KtOperationExpression -> parent


                    is KtBlockExpression -> return expr
                    is KtIfExpression -> return expr
                    is KtWhileExpression -> return expr
                    is KtDoWhileExpression -> return expr
                    else -> return expr
                }
            }
        }

    }

}

