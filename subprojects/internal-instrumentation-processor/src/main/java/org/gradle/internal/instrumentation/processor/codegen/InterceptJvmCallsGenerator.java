/*
 * Copyright 2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.instrumentation.processor.codegen;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeSpec;
import org.gradle.internal.instrumentation.api.jvmbytecode.JvmBytecodeCallInterceptor;
import org.gradle.internal.instrumentation.api.annotations.CallableKind;
import org.gradle.internal.instrumentation.api.annotations.ParameterKind;
import org.gradle.internal.instrumentation.model.CallInterceptionRequest;
import org.gradle.internal.instrumentation.model.CallableInfo;
import org.gradle.internal.instrumentation.model.CallableKindInfo;
import org.gradle.internal.instrumentation.model.ParameterInfo;
import org.gradle.internal.instrumentation.model.ParameterKindInfo;
import org.gradle.internal.instrumentation.model.RequestFlag;
import org.gradle.internal.instrumentation.processor.codegen.InstrumentationCodeGenerator.GenerationResult.HasFailures.FailureInfo;
import org.gradle.internal.instrumentation.utils.LocalVariablesSorterWithDroppedVariables;
import org.gradle.model.internal.asm.MethodVisitorScope;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import javax.lang.model.element.Modifier;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.gradle.internal.instrumentation.processor.codegen.TypeUtils.typeName;
import static org.gradle.util.internal.TextUtil.camelToKebabCase;

public class InterceptJvmCallsGenerator extends RequestGroupingInstrumentationClassGenerator {
    @Override
    protected String classNameForRequest(CallInterceptionRequest request) {
        Optional<RequestFlag> jvmRequestFlag = request.getRequestFlags().stream().filter(flag -> flag instanceof RequestFlag.InterceptJvmCalls).findAny();
        return jvmRequestFlag.map(flag -> ((RequestFlag.InterceptJvmCalls) flag).getImplementationClassName()).orElse(null);
    }

    @Override
    protected Consumer<TypeSpec.Builder> classContentForClass(
        String className,
        Collection<CallInterceptionRequest> requestsClassGroup,
        Consumer<? super CallInterceptionRequest> onProcessedRequest,
        Consumer<? super FailureInfo> onFailure
    ) {
        Map<Type, FieldSpec> typeFieldByOwner = generateFieldsForImplementationOwners(requestsClassGroup);

        MethodSpec.Builder visitMethodInsnBuilder = getVisitMethodInsnBuilder();
        generateVisitMethodInsnCode(
            visitMethodInsnBuilder, requestsClassGroup, typeFieldByOwner, onProcessedRequest, onFailure
        );

        return builder ->
            builder.addMethod(constructor)
                // generic stuff not related to the content:
                .addSuperinterface(JvmBytecodeCallInterceptor.class)
                .addMethod(binaryClassNameOf)
                .addMethod(loadBinaryClassName)
                .addField(localVariablesSorterField)
                .addField(unmappedMv)
                .superclass(MethodVisitorScope.class)
                // actual content:
                .addMethod(visitMethodInsnBuilder.build())
                .addFields(typeFieldByOwner.values());
    }


    private static void generateVisitMethodInsnCode(
        MethodSpec.Builder visitMethodInsnBuilder,
        Collection<CallInterceptionRequest> interceptionRequests,
        Map<Type, FieldSpec> typeFieldByOwner,
        Consumer<? super CallInterceptionRequest> onProcessedRequest,
        Consumer<? super FailureInfo> onFailure
    ) {
        CodeBlock.Builder code = CodeBlock.builder();
        interceptionRequests.stream()
            .collect(Collectors.groupingBy(it -> it.getInterceptedCallable().getOwner(), LinkedHashMap::new, Collectors.toList()))
            .forEach((owner, requests) -> generateCodeForOwner(owner, typeFieldByOwner, requests, code, onProcessedRequest, onFailure));
        code.addStatement("return false");
        visitMethodInsnBuilder.addCode(code.build());
    }

    @NotNull
    private static Map<Type, FieldSpec> generateFieldsForImplementationOwners(Collection<CallInterceptionRequest> interceptionRequests) {
        Set<String> knownSimpleNames = new HashSet<>();
        return interceptionRequests.stream().map(CallInterceptionRequest::getImplementationOwner).distinct()
            .collect(Collectors.toMap(Function.identity(), implementationType -> {
                ClassName implementationClassName = ClassName.bestGuess(implementationType.getClassName());
                String fieldTypeName = knownSimpleNames.add(implementationClassName.simpleName()) ?
                    implementationClassName.simpleName() :
                    implementationClassName.canonicalName();
                String fullFieldName = camelToKebabCase(fieldTypeName).replace("-", "_").toUpperCase(Locale.US) + "_TYPE";
                return FieldSpec.builder(Type.class, fullFieldName, Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                    .initializer("$T.getType($T.class)", Type.class, implementationClassName)
                    .build();
            }, (u, v) -> u, LinkedHashMap::new));
    }

    MethodSpec constructor = MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC)
        .addParameter(MethodVisitor.class, "unmappedMethodVisitor")
        .addParameter(LocalVariablesSorterWithDroppedVariables.class, "localVariablesSorter")
        .addStatement("super(localVariablesSorter)")
        .addStatement("this.$N = localVariablesSorter", localVariablesSorterField)
        .addStatement("this.$N = unmappedMethodVisitor", unmappedMv)
        .build();

    @NotNull
    private static MethodSpec.Builder getVisitMethodInsnBuilder() {
        return MethodSpec.methodBuilder("visitMethodInsn")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(boolean.class)
            .addParameter(String.class, "className")
            .addParameter(int.class, "opcode")
            .addParameter(String.class, "owner")
            .addParameter(String.class, "name")
            .addParameter(String.class, "descriptor")
            .addParameter(boolean.class, "isInterface");
    }

    private static final MethodSpec binaryClassNameOf = MethodSpec.methodBuilder("binaryClassNameOf")
        .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
        .returns(String.class)
        .addParameter(String.class, "className")
        .addStatement("return $T.getObjectType(className).getClassName()", Type.class)
        .build();

    private static final MethodSpec loadBinaryClassName = MethodSpec.methodBuilder("loadOwnerBinaryClassName")
        .addModifiers(Modifier.PRIVATE)
        .returns(void.class)
        .addParameter(String.class, "className")
        .addStatement("_LDC($N(className))", binaryClassNameOf)
        .build();

    private static final FieldSpec localVariablesSorterField = FieldSpec.builder(LocalVariablesSorterWithDroppedVariables.class, "localVariablesSorter", Modifier.PRIVATE, Modifier.FINAL).build();
    private static final FieldSpec unmappedMv =
        FieldSpec.builder(MethodVisitor.class, "unmappedMethodVisitor", Modifier.PRIVATE, Modifier.FINAL)
            .addJavadoc("This is the method visitor that does not map the local variables with the {@link $N} and can thus use the newly introduced variables.", localVariablesSorterField).build();

    private static void generateCodeForOwner(
        Type owner,
        Map<Type, FieldSpec> implTypeFields,
        List<CallInterceptionRequest> requestsForOwner,
        CodeBlock.Builder code,
        Consumer<? super CallInterceptionRequest> onProcessedRequest,
        Consumer<? super FailureInfo> onFailure
    ) {
        code.beginControlFlow("if (owner.equals($S))", owner.getInternalName());
        for (CallInterceptionRequest request : requestsForOwner) {
            CodeBlock.Builder nested = CodeBlock.builder();
            try {
                generateCodeForRequest(request, implTypeFields.get(request.getImplementationOwner()), nested);
            } catch (Failure failure) {
                onFailure.accept(new FailureInfo(request, failure.reason));
            }
            onProcessedRequest.accept(request);
            code.add(nested.build());
        }
        code.endControlFlow();
    }

    private static void generateCodeForRequest(CallInterceptionRequest request, FieldSpec implTypeField, CodeBlock.Builder code) {
        String callableName = request.getInterceptedCallable().getCallableName();
        CallableInfo interceptedCallable = request.getInterceptedCallable();
        String interceptedCallableDescriptor = standardCallableDescriptor(interceptedCallable);
        validateSignature(request.getInterceptedCallable());

        CodeBlock matchOpcodeExpression = matchOpcodeExpression(interceptedCallable);

        documentInterceptorGeneratedCode(request, code);
        matchAndInterceptStandardCallableSignature(request, implTypeField, code, callableName, interceptedCallableDescriptor, matchOpcodeExpression);

        if (interceptedCallable.getParameters().stream().anyMatch(it -> it.getKind() == ParameterKindInfo.KOTLIN_DEFAULT_MASK)) {
            matchAndInterceptKotlinDefaultSignature(request, implTypeField, code, callableName, interceptedCallable, matchOpcodeExpression);
        }
    }

    private static void matchAndInterceptStandardCallableSignature(
        CallInterceptionRequest request,
        FieldSpec implTypeField,
        CodeBlock.Builder code,
        String callableName,
        String callableDescriptor,
        CodeBlock matchOpcodeExpression
    ) {
        code.beginControlFlow("if (name.equals($S) && descriptor.equals($S) && $L)", callableName, callableDescriptor, matchOpcodeExpression);
        generateInterceptedInvocation(request, implTypeField, code);
        code.addStatement("return true");
        code.endControlFlow();
    }

    private static void matchAndInterceptKotlinDefaultSignature(
        CallInterceptionRequest request,
        FieldSpec ownerTypeField,
        CodeBlock.Builder code,
        String callableName,
        CallableInfo interceptedCallable,
        CodeBlock matchOpcodeExpression
    ) {
        code.add("// Additionally intercept the signature with the Kotlin default mask and marker:\n");
        String callableDescriptorKotlinDefault = kotlinDefaultFunctionDescriptor(interceptedCallable);
        String defaultMethodName = callableName + "$default";
        code.beginControlFlow("if (name.equals($S) && descriptor.equals($S) && $L)", defaultMethodName, callableDescriptorKotlinDefault, matchOpcodeExpression);
        generateKotlinDefaultInvocation(request, ownerTypeField, code);
        code.addStatement("return true");
        code.endControlFlow();
    }

    private static void documentInterceptorGeneratedCode(CallInterceptionRequest request, CodeBlock.Builder code) {
        CallableInfo interceptedCallable = request.getInterceptedCallable();
        List<ParameterInfo> params = interceptedCallable.getParameters();

        String callableKindString = interceptedCallable.getKind() == CallableKindInfo.STATIC_METHOD ? "static method" :
            interceptedCallable.getKind() == CallableKindInfo.INSTANCE_METHOD ? "instance method" :
                interceptedCallable.getKind() == CallableKindInfo.AFTER_CONSTRUCTOR ? "constructor (getting notified after it)" : null;

        ClassName className = ClassName.bestGuess(interceptedCallable.getOwner().getClassName());
        String callableNameForDocComment = interceptedCallable.getKind() == CallableKindInfo.AFTER_CONSTRUCTOR ? className.simpleName() : interceptedCallable.getCallableName();
        code.add("/** \n * Intercepting $L: {@link $T#$L(", callableKindString, className, callableNameForDocComment);
        List<ParameterInfo> methodParameters = params.stream().filter(parameter -> parameter.getKind() == ParameterKindInfo.METHOD_PARAMETER).collect(Collectors.toList());
        methodParameters.forEach(parameter -> {
            code.add("$T", typeName(parameter.getParameterType()));
            if (parameter != methodParameters.get(methodParameters.size() - 1)) {
                code.add(", ");
            }
        });
        code.add(")}\n");

        code.add(" * Intercepted by {@link $T#$L(", ClassName.bestGuess(request.getImplementationOwner().getClassName()), request.getImplementationName());
        params.forEach(parameter -> {
            code.add("$T", typeName(parameter.getParameterType()));
            if (parameter != params.get(params.size() - 1)) {
                code.add(", ");
            }
        });
        code.add(")}\n*/\n");
    }

    private static CodeBlock matchOpcodeExpression(CallableInfo interceptedCallable) {
        CodeBlock result = interceptedCallable.getKind() == CallableKindInfo.STATIC_METHOD ? CodeBlock.of("opcode == $T.INVOKESTATIC", Opcodes.class) :
            interceptedCallable.getKind() == CallableKindInfo.INSTANCE_METHOD ? CodeBlock.of("opcode == $T.INVOKEVIRTUAL", Opcodes.class) :
                interceptedCallable.getKind() == CallableKindInfo.AFTER_CONSTRUCTOR ? CodeBlock.of("opcode == $T.INVOKESPECIAL", Opcodes.class) : null;
        if (result == null) {
            throw new Failure("Could not determine the opcode for intercepting the call");
        }
        return result;
    }

    // TODO: move validation earlier?
    private static void generateInterceptedInvocation(CallInterceptionRequest request, FieldSpec implTypeField, CodeBlock.Builder method) {
        CallableInfo callable = request.getInterceptedCallable();
        String implementationName = request.getImplementationName();
        String implementationDescriptor = request.getImplementationDescriptor();

        if (callable.getKind() == CallableKindInfo.STATIC_METHOD || callable.getKind() == CallableKindInfo.INSTANCE_METHOD) {
            generateNormalInterceptedInvocation(implTypeField, callable, implementationName, implementationDescriptor, method);
        } else if (callable.getKind() == CallableKindInfo.AFTER_CONSTRUCTOR) {
            generateInvocationAfterConstructor(implTypeField, method, callable, implementationName, implementationDescriptor);
        }
    }

    private static void generateInvocationAfterConstructor(FieldSpec implOwnerField, CodeBlock.Builder code, CallableInfo callable, String implementationName, String implementationDescriptor) {
        if (callable.getKind() != CallableKindInfo.AFTER_CONSTRUCTOR) {
            throw new IllegalArgumentException("expected after-constructor interceptor");
        }

        List<ParameterInfo> parameters = callable.getParameters();
        if (parameters.get(0).getKind() != ParameterKindInfo.RECEIVER) {
            throw new Failure("Expected @" + ParameterKind.Receiver.class.getSimpleName() + " first parameter in @" + CallableKind.AfterConstructor.class.getSimpleName());
        }
        if (!Type.getReturnType(implementationDescriptor).equals(Type.VOID_TYPE)) {
            throw new Failure("@" + CallableKind.AfterConstructor.class.getSimpleName() + " handlers can only return void");
        }

        // Store the constructor arguments in local variables, so that we can duplicate them for both the constructor and the interceptor:
        Type[] params = Type.getArgumentTypes(standardCallableDescriptor(callable));
        for (int i = params.length - 1; i >= 0; i--) {
            code.addStatement("$1T type$2L = $1T.getType($3T.class)", Type.class, i, typeName(params[i]));
            code.addStatement("int var$1L = $2N.newLocal(type$1L)", i, localVariablesSorterField);
            code.addStatement("$1N.visitVarInsn(type$2L.getOpcode($3T.ISTORE), var$2L)", unmappedMv, i, Opcodes.class);
        }
        // Duplicate the receiver without storing it into a local variable, then prepare the arguments for the original invocation:
        code.addStatement("_DUP()");
        for (int i = 0; i < params.length; i++) {
            code.addStatement("$1N.visitVarInsn(type$2L.getOpcode($3T.ILOAD), var$2L)", unmappedMv, i, Opcodes.class);
        }
        // Put the arguments to the stack again, for the "interceptor" invocation:
        code.addStatement("_INVOKESPECIAL(owner, name, descriptor)");
        for (int i = 0; i < params.length; i++) {
            code.addStatement("$1N.visitVarInsn(type$2L.getOpcode($3T.ILOAD), var$2L)", unmappedMv, i, Opcodes.class);
            // The new local variable is out of scope now, drop it to maintain the stack frame correctness:
            code.addStatement("$1N.dropLocal(var$2L)", localVariablesSorterField, i);
        }
        code.addStatement("$N(className)", loadBinaryClassName);
        code.addStatement("_INVOKESTATIC($N, $S, $S)", implOwnerField, implementationName, implementationDescriptor);
    }

    private static void generateNormalInterceptedInvocation(FieldSpec ownerTypeField, CallableInfo callable, String implementationName, String implementationDescriptor, CodeBlock.Builder code) {
        if (callable.getKind() == CallableKindInfo.GROOVY_PROPERTY) {
            throw new IllegalArgumentException("cannot generate invocation for Groovy property");
        }

        List<ParameterInfo> parameters = callable.getParameters();
        if (parameters.size() > 1 && parameters.get(parameters.size() - 2).getKind() == ParameterKindInfo.KOTLIN_DEFAULT_MASK) {
            // push the default mask equal to zero, meaning that no parameters have the default values
            code.add("// The interceptor expects a Kotlin default mask, add a zero argument:\n");
            code.addStatement("_ICONST_0()");
        }
        code.addStatement("_LDC($N(className))", binaryClassNameOf);
        code.addStatement("_INVOKESTATIC($N, $S, $S)", ownerTypeField, implementationName, implementationDescriptor);
    }

    private static void generateKotlinDefaultInvocation(CallInterceptionRequest request, FieldSpec ownerTypeField, CodeBlock.Builder method) {
        CallableInfo interceptedCallable = request.getInterceptedCallable();
        if (interceptedCallable.getKind() == CallableKindInfo.GROOVY_PROPERTY) {
            throw new IllegalArgumentException("cannot generate invocation for Groovy property");
        }

        String implementationName = request.getImplementationName();
        String implementationDescriptor = request.getImplementationDescriptor();

        method.addStatement("_POP()"); // pops the default method signature marker
        method.addStatement("_LDC($N(className))", binaryClassNameOf);
        method.addStatement("_INVOKESTATIC($N, $S, $S)", ownerTypeField, implementationName, implementationDescriptor);
    }

    private static void validateSignature(CallableInfo callable) {
        if (callable.getKind() == CallableKindInfo.GROOVY_PROPERTY) {
            throw new Failure("Groovy property access cannot be intercepted in JVM calls");
        }

        Optional<ParameterInfo> maybeCallerClassNameParam = callable.getParameters().stream().filter(it -> it.getKind() == ParameterKindInfo.CALLER_CLASS_NAME).findFirst();
        if (!maybeCallerClassNameParam.isPresent()) {
            throw new Failure("The interceptor should have a @" + ParameterKind.CallerClassName.class.getSimpleName() + " parameter");
        }
        if (callable.getParameters().indexOf(maybeCallerClassNameParam.get()) != callable.getParameters().size() - 1) {
            throw new Failure("The interceptor's caller class name parameter should go last");
        }

        if (callable.getParameters().stream().anyMatch(it -> it.getKind() == ParameterKindInfo.KOTLIN_DEFAULT_MASK)) {
            // TODO support @AfterConstructor with Kotlin default mask? Kotlin constructors have a special DefaultConstructorMarker as the last argument
            if (callable.getKind() != CallableKindInfo.STATIC_METHOD && callable.getKind() != CallableKindInfo.INSTANCE_METHOD) {
                throw new Failure(
                    "Only @" + CallableKind.StaticMethod.class.getSimpleName() + " or " + CallableKind.InstanceMethod.class.getSimpleName() + " can use Kotlin default parameters"
                );
            }

            if (callable.getParameters().get(callable.getParameters().size() - 2).getKind() != ParameterKindInfo.KOTLIN_DEFAULT_MASK) {
                throw new Failure("@" + ParameterKind.KotlinDefaultMask.class.getSimpleName() + " should be the second to last parameter");
            }
        }
    }

    private static String standardCallableDescriptor(CallableInfo callableInfo) {
        Type[] parameterTypes = callableInfo.getParameters().stream()
            .filter(it -> it.getKind() == ParameterKindInfo.METHOD_PARAMETER)
            .map(ParameterInfo::getParameterType).toArray(Type[]::new);
        Type returnType = callableInfo.getReturnType();
        return Type.getMethodDescriptor(returnType, parameterTypes);
    }

    private static String kotlinDefaultFunctionDescriptor(CallableInfo callableInfo) {
        if (callableInfo.getKind() != CallableKindInfo.INSTANCE_METHOD && callableInfo.getKind() != CallableKindInfo.STATIC_METHOD) {
            throw new UnsupportedOperationException("Kotlin default parameters are not yet supported for " + callableInfo.getKind());
        }

        String standardDescriptor = standardCallableDescriptor(callableInfo);
        Type returnType = Type.getReturnType(standardDescriptor);
        Type[] argumentTypes = Type.getArgumentTypes(standardDescriptor);
        Type[] argumentTypesWithDefault = Stream.concat(
            Arrays.stream(argumentTypes),
            Stream.of(Type.getType(int.class), Type.getType(Object.class))
        ).toArray(Type[]::new);
        return Type.getMethodDescriptor(returnType, argumentTypesWithDefault);
    }

    private static class Failure extends RuntimeException {
        final String reason;

        private Failure(String reason) {this.reason = reason;}
    }
}
