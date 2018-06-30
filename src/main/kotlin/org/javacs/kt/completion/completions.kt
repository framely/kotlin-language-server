package org.javacs.kt.completion

// TODO: Clean up imports

import com.google.common.cache.CacheBuilder
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.Position
import org.javacs.kt.CompiledFile
import org.javacs.kt.LOG
import org.javacs.kt.util.findParent
import org.javacs.kt.util.noResult
import org.javacs.kt.util.toPath
import org.jetbrains.kotlin.container.get
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.js.resolve.diagnostics.findPsi
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.parentsWithSelf
import org.jetbrains.kotlin.psi.psiUtil.*
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.resolve.descriptorUtil.isExtension
import org.jetbrains.kotlin.resolve.descriptorUtil.parentsWithSelf
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter.Companion
import org.jetbrains.kotlin.resolve.scopes.HierarchicalScope
import org.jetbrains.kotlin.resolve.scopes.LexicalScope
import org.jetbrains.kotlin.resolve.scopes.getDescriptorsFiltered
import org.jetbrains.kotlin.resolve.scopes.utils.parentsWithSelf
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.TypeUtils
import org.jetbrains.kotlin.types.typeUtil.supertypes
import java.util.concurrent.TimeUnit

private const val MAX_COMPLETION_ITEMS = 50

fun completions(file: CompiledFile, position: Position): CompletionList {
    val compiler = file.classPath.compiler
    val provider = CompletionProvider(file.sourcePath.toMutableList(), file.parse, position.line, position.character, compiler)
    val list = provider
            .getResult()
            .map { it.descriptor?.accept(RenderCompletionItem(), Unit) }
            .filterNotNull() // TODO: This will filter out all keyword completions that do not have a descriptor which might not be desirable
            .take(MAX_COMPLETION_ITEMS)
            .toList()
    val isIncomplete = (list.size == MAX_COMPLETION_ITEMS)
    return CompletionList(isIncomplete, list)
}

fun memberOverloads(type: KotlinType, identifier: String): Sequence<CallableDescriptor> {
    val nameFilter = equalsIdentifier(identifier)

    return type.memberScope
            .getContributedDescriptors(Companion.CALLABLES).asSequence()
            .filterIsInstance<CallableDescriptor>()
            .filter(nameFilter)
}

fun identifierOverloads(scope: LexicalScope, identifier: String): Sequence<CallableDescriptor> {
    val nameFilter = equalsIdentifier(identifier)

    return identifiers(scope)
            .filterIsInstance<CallableDescriptor>()
            .filter(nameFilter)
}

private fun identifiers(scope: LexicalScope): Sequence<DeclarationDescriptor> =
    scope.parentsWithSelf
            .flatMap(::scopeIdentifiers)
            .flatMap(::explodeConstructors)

private fun scopeIdentifiers(scope: HierarchicalScope): Sequence<DeclarationDescriptor> {
    val locals = scope.getContributedDescriptors().asSequence()
    val members = implicitMembers(scope)

    return locals + members
}

private fun explodeConstructors(declaration: DeclarationDescriptor): Sequence<DeclarationDescriptor> {
    return when (declaration) {
        is ClassDescriptor ->
            declaration.constructors.asSequence() + declaration
        else ->
            sequenceOf(declaration)
    }
}

private fun implicitMembers(scope: HierarchicalScope): Sequence<DeclarationDescriptor> {
    if (scope !is LexicalScope) return emptySequence()
    val implicit = scope.implicitReceiver ?: return emptySequence()

    return implicit.type.memberScope.getContributedDescriptors().asSequence()
}

private fun equalsIdentifier(identifier: String): (DeclarationDescriptor) -> Boolean {
    return { name(it) == identifier }
}

private fun name(d: DeclarationDescriptor): String {
    if (d is ConstructorDescriptor)
        return d.constructedClass.name.identifier
    else
        return d.name.identifier
}

fun containsCharactersInOrder(
        candidate: CharSequence, pattern: CharSequence, caseSensitive: Boolean): Boolean {
    var iCandidate = 0
    var iPattern = 0

    while (iCandidate < candidate.length && iPattern < pattern.length) {
        var patternChar = pattern[iPattern]
        var testChar = candidate[iCandidate]

        if (!caseSensitive) {
            patternChar = Character.toLowerCase(patternChar)
            testChar = Character.toLowerCase(testChar)
        }

        if (patternChar == testChar) {
            iPattern++
            iCandidate++
        } else iCandidate++
    }

    return iPattern == pattern.length
}
