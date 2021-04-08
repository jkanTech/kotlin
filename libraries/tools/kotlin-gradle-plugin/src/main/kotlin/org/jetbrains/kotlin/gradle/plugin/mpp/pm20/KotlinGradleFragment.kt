/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.plugin.mpp.pm20

import groovy.lang.Closure
import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectProvider
import org.gradle.api.Project
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.provider.Provider
import org.gradle.util.ConfigureUtil
import org.jetbrains.kotlin.gradle.plugin.HasKotlinDependencies
import org.jetbrains.kotlin.gradle.plugin.KotlinDependencyHandler
import org.jetbrains.kotlin.gradle.plugin.LanguageSettingsBuilder
import org.jetbrains.kotlin.gradle.plugin.mpp.DefaultKotlinDependencyHandler
import org.jetbrains.kotlin.gradle.plugin.sources.DefaultLanguageSettingsBuilder
import org.jetbrains.kotlin.gradle.utils.addExtendsFromRelation
import org.jetbrains.kotlin.gradle.utils.lowerCamelCaseName
import org.jetbrains.kotlin.project.model.KotlinModuleDependency
import org.jetbrains.kotlin.project.model.KotlinModuleFragment
import javax.inject.Inject

open class KotlinGradleFragment @Inject constructor(
    override val containingModule: KotlinGradleModule,
    override val fragmentName: String
) : KotlinModuleFragment, HasKotlinDependencies, Named {

    override fun getName(): String = fragmentName

    // TODO pull up to KotlinModuleFragment
    val languageSettings: LanguageSettingsBuilder = DefaultLanguageSettingsBuilder()

    protected val project: Project
        get() = containingModule.project

    open fun refines(other: KotlinGradleFragment) {
        checkCanRefine(other)
        refines(containingModule.fragments.named(other.name))
    }

    open fun refines(other: NamedDomainObjectProvider<KotlinGradleFragment>) {
        _directRefinesDependencies.add(other)
        other.configure { checkCanRefine(it) }
        listOf(
            KotlinGradleFragment::transitiveApiConfigurationName,
            KotlinGradleFragment::transitiveImplementationConfigurationName
        ).forEach { getConfiguration ->
            project.addExtendsFromRelation(getConfiguration(this), getConfiguration(other.get())) // todo eager instantiation; fix?
        }
    }

    private fun checkCanRefine(other: KotlinGradleFragment) {
        check(containingModule == other.containingModule) { "Fragments can only refine each other within one module." }
    }

    override fun dependencies(configure: KotlinDependencyHandler.() -> Unit): Unit =
        DefaultKotlinDependencyHandler(this, project).run(configure)

    override fun dependencies(configureClosure: Closure<Any?>) =
        dependencies f@{ ConfigureUtil.configure(configureClosure, this@f) }

    override val apiConfigurationName: String
        get() = disambiguateName("api")

    override val implementationConfigurationName: String
        get() = disambiguateName("implementation")

    override val compileOnlyConfigurationName: String
        get() = disambiguateName("compileOnly")

    override val runtimeOnlyConfigurationName: String
        get() = disambiguateName("runtimeOnly")

    private val _directRefinesDependencies = mutableSetOf<Provider<KotlinGradleFragment>>()

    override val directRefinesDependencies: Iterable<KotlinGradleFragment>
        get() = _directRefinesDependencies.map { it.get() }

    override val declaredModuleDependencies: Iterable<KotlinModuleDependency>
        get() = TODO("Not yet implemented")

    override val kotlinSourceRoots: SourceDirectorySet by lazy {
        project.objects.sourceDirectorySet(
            "$fragmentName.kotlin", "Kotlin sources for fragment $fragmentName"
        )
    }

    /** This configuration includes the dependencies from the refines-parents */
    internal val transitiveApiConfigurationName: String
        get() = disambiguateName("transitiveApi")

    /** This configuration includes the dependencies from the refines-parents */
    internal val transitiveImplementationConfigurationName: String
        get() = disambiguateName("transitiveImplementation")

    override val relatedConfigurationNames: List<String>
        get() = super.relatedConfigurationNames +
                // TODO: resolvable metadata configurations?
                listOf(transitiveApiConfigurationName, transitiveImplementationConfigurationName)

    companion object {
        const val COMMON_FRAGMENT_NAME = "common"
    }
}

internal fun KotlinModuleFragment.disambiguateName(simpleName: String) =
    lowerCamelCaseName(fragmentName, containingModule.moduleIdentifier.moduleClassifier ?: KotlinGradleModule.MAIN_MODULE_NAME, simpleName)
