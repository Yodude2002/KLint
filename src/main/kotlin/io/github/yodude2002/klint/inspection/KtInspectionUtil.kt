package io.github.yodude2002.klint.inspection

import com.intellij.lang.java.JavaLanguage
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTypesUtil
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.idea.caches.resolve.resolveToCall
import org.jetbrains.kotlin.idea.structuralsearch.resolveExprType
import org.jetbrains.kotlin.idea.structuralsearch.resolveReceiverType
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassLiteralExpression
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.resolve.source.getPsi
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlinx.serialization.compiler.backend.common.serialName

fun KtAnnotationEntry.isThrowsAnnotation(): Boolean {
    return setOf("kotlin.jvm.Throws", "kotlin.Throws")
        .contains(this.calleeExpression
            ?.resolveReceiverType()
            ?.serialName()
        )
}
fun KtFunction.annotatedThrows(): List<KotlinType> {
    return this.annotationEntries
            .filterNotNull()
            .mapNotNull { throwsFromAnnotation(it) }
            .flatten()
}
fun throwsFromAnnotation(annotation: KtAnnotationEntry): List<KotlinType>? {
    if (!annotation.isThrowsAnnotation()) return null

    return annotation.valueArgumentList
            ?.arguments
            ?.map { it.getArgumentExpression() }
            ?.filterIsInstance<KtClassLiteralExpression>()
            ?.mapNotNull { it.resolveExprType()?.arguments?.getOrNull(0)?.type }
            ?: listOf()
}

fun PsiMethod.throwsTypes(): List<PsiClass> {
    return this.throwsList
            .referencedTypes
            .mapNotNull { it.resolve() }
}

fun KtCallExpression.getJavaCaller(): PsiMethod? {
    val sourcePsi = this.resolveToCall()
        ?.candidateDescriptor
        ?.source
        ?.getPsi()
        ?: return null

    if (sourcePsi.language != JavaLanguage.INSTANCE) return null
    return sourcePsi as? PsiMethod
}

fun PsiClass.getClassType(): PsiClassType {
    return PsiTypesUtil.getClassType(this)
}

fun KtCallExpression.getKotlinCaller(): KtFunction? {
    val sourcePsi = this.resolveToCall()
        ?.candidateDescriptor
        ?.source
        ?.getPsi()
        ?: return null

    if (sourcePsi.language != KotlinLanguage.INSTANCE) return null
    return sourcePsi as? KtFunction
}