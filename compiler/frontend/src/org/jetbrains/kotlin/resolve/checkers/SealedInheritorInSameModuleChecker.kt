/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.resolve.checkers

import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ClassifierDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.isSealed
import org.jetbrains.kotlin.diagnostics.Errors
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.bindingContextUtil.getAbbreviatedTypeOrType
import org.jetbrains.kotlin.resolve.descriptorUtil.module
import org.jetbrains.kotlin.resolve.multiplatform.commonSourceSetName
import org.jetbrains.kotlin.resolve.source.PsiSourceFile

object SealedInheritorInSameModuleChecker : DeclarationChecker {
    override fun check(declaration: KtDeclaration, descriptor: DeclarationDescriptor, context: DeclarationCheckerContext) {
        if (descriptor !is ClassDescriptor || declaration !is KtClassOrObject) return
        val descriptorSourceSet = descriptor.sourceSet
        val currentModule = descriptor.module
        for (superTypeListEntry in declaration.superTypeListEntries) {
            val typeReference = superTypeListEntry.typeReference ?: continue
            val superType = typeReference.getAbbreviatedTypeOrType(context.trace.bindingContext)?.unwrap() ?: continue
            val superClass = superType.constructor.declarationDescriptor ?: continue
            if (superClass.isSealed()) {
                /*
                 * It's allowed to declare inheritors of expect sealed class in any dependent module until actual
                 *   counterpart for this class will be declared. So if super class is resolved to expect sealed
                 *   class then its allowed to declare inheritor
                 */
                val inheritorAllowed =
                    superClass.isExpect || (superClass.module == currentModule && descriptorSourceSet == superClass.sourceSet)
                if (!inheritorAllowed) {
                    context.trace.report(Errors.SEALED_INHERITOR_IN_DIFFERENT_MODULE.on(typeReference))
                }
            }
        }
    }

    private val ClassifierDescriptor.sourceSet: String?
        get() = ((this.source.containingFile as? PsiSourceFile)?.psiFile as? KtFile)?.commonSourceSetName
}
