package org.checkerframework.checker.formatter;

/*>>>
import org.checkerframework.checker.compilermsgs.qual.CompilerMessageKey;
*/

import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.util.SimpleTreeVisitor;
import java.util.IllegalFormatException;
import java.util.List;
import java.util.Locale;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.NullType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.SimpleElementVisitor7;
import javax.lang.model.util.SimpleTypeVisitor7;
import org.checkerframework.checker.formatter.qual.ConversionCategory;
import org.checkerframework.checker.formatter.qual.Format;
import org.checkerframework.checker.formatter.qual.FormatMethod;
import org.checkerframework.checker.formatter.qual.InvalidFormat;
import org.checkerframework.checker.formatter.qual.ReturnsFormat;
import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.dataflow.cfg.node.ArrayCreationNode;
import org.checkerframework.dataflow.cfg.node.FieldAccessNode;
import org.checkerframework.dataflow.cfg.node.MethodInvocationNode;
import org.checkerframework.dataflow.cfg.node.Node;
import org.checkerframework.framework.type.AnnotatedTypeFactory;
import org.checkerframework.framework.type.AnnotatedTypeMirror;
import org.checkerframework.framework.util.AnnotationBuilder;
import org.checkerframework.javacutil.AnnotationUtils;
import org.checkerframework.javacutil.TreeUtils;

/**
 * This class provides a collection of utilities to ease working
 * with syntax trees that have something to do with Formatters.
 *
 * @author Konstantin Weitz
 */
public class FormatterTreeUtil {
    public final BaseTypeChecker checker;
    public final ProcessingEnvironment processingEnv;

    // private final ExecutableElement formatArgTypesElement;

    public FormatterTreeUtil(BaseTypeChecker checker) {
        this.checker = checker;
        this.processingEnv = checker.getProcessingEnvironment();
        /*
        this.formatArgTypesElement =
                TreeUtils.getMethod(
                        org.checkerframework.checker.formatter.qual.Format.class.getCanonicalName(),
                        "value",
                        0,
                        processingEnv);
         */
    }

    /**
     * Describes the ways a format method may be invoked.
     */
    public enum InvocationType {
        /**
         * The parameters are passed as varargs. For example:
         *
         * <blockquote>
         * <pre>
         * String.format("%s %d", "Example", 7);
         * </pre>
         * </blockquote>
         */
        VARARG,

        /**
         * The parameters are passed as array. For example:
         *
         * <blockquote>
         * <pre>
         * Object[] a = new Object[]{"Example",7};
         * String.format("%s %d", a);
         * </pre>
         * </blockquote>
         */
        ARRAY,

        /**
         * A null array is passed to the format method.
         * This happens seldomly.
         *
         * <blockquote>
         * <pre>
         * String.format("%s %d", (Object[])null);
         * </pre>
         * </blockquote>
         */
        NULLARRAY;
    }

    public interface Result<E> {
        E value();
    }

    private static class ResultImpl<E> implements Result<E> {
        private final E value;
        public final ExpressionTree location;

        public ResultImpl(E value, ExpressionTree location) {
            this.value = value;
            this.location = location;
        }

        @Override
        public E value() {
            return value;
        }
    }

    public boolean isAsFormatCall(MethodInvocationNode node, AnnotatedTypeFactory atypeFactory) {
        ExecutableElement method = node.getTarget().getMethod();
        AnnotationMirror anno = atypeFactory.getDeclAnnotation(method, ReturnsFormat.class);
        return anno != null;
    }

    private ConversionCategory[] asFormatCallCategoriesLowLevel(MethodInvocationNode node) {
        Node vararg = node.getArgument(1);
        if (vararg instanceof ArrayCreationNode) {
            List<Node> convs = ((ArrayCreationNode) vararg).getInitializers();
            ConversionCategory[] res = new ConversionCategory[convs.size()];
            for (int i = 0; i < convs.size(); ++i) {
                Node conv = convs.get(i);
                if (conv instanceof FieldAccessNode) {
                    Class<? extends Object> clazz =
                            typeMirrorToClass(((FieldAccessNode) conv).getType());
                    if (clazz == ConversionCategory.class) {
                        res[i] =
                                ConversionCategory.valueOf(((FieldAccessNode) conv).getFieldName());
                        continue; /* avoid returning null */
                    }
                }
                return null;
            }
            return res;
        }
        return null;
    }

    public Result<ConversionCategory[]> asFormatCallCategories(MethodInvocationNode node) {
        // TODO make sure the method signature looks good
        return new ResultImpl<ConversionCategory[]>(
                asFormatCallCategoriesLowLevel(node), node.getTree());
    }

    public boolean isFormatCall(MethodInvocationTree node, AnnotatedTypeFactory atypeFactory) {
        ExecutableElement method = TreeUtils.elementFromUse(node);
        AnnotationMirror anno = atypeFactory.getDeclAnnotation(method, FormatMethod.class);
        return anno != null;
    }

    /**
     * Represents a format method invocation in the syntax tree.
     */
    public class FormatCall {
        private final AnnotatedTypeMirror formatAnno;
        private final List<? extends ExpressionTree> args;
        private final MethodInvocationTree node;
        private final ExpressionTree formatArg;
        private final AnnotatedTypeFactory atypeFactory;

        public FormatCall(MethodInvocationTree node, AnnotatedTypeFactory atypeFactory) {
            this.node = node;
            // TODO figure out how to make passing of environment
            // objects such as atypeFactory, processingEnv, ... nicer
            this.atypeFactory = atypeFactory;
            List<? extends ExpressionTree> theargs;
            theargs = node.getArguments();

            if (typeMirrorToClass(atypeFactory.getAnnotatedType(theargs.get(0)).getUnderlyingType())
                    == Locale.class) {
                // call with Locale as first argument
                theargs = theargs.subList(1, theargs.size());
            }

            // TODO check that the first parameter exists and is a string
            formatArg = theargs.get(0);
            formatAnno = atypeFactory.getAnnotatedType(formatArg);
            this.args = theargs.subList(1, theargs.size());
        }

        /**
         * Returns an error description if the format string cannot be satisfied.
         * Returns null if the format string does not contain syntactic errors.
         */
        public final Result<String> isIllegalFormat() {
            String res = null;
            if (!formatAnno.hasAnnotation(Format.class)) {
                res = "(is a @Format annotation missing?)";
                AnnotationMirror inv = formatAnno.getAnnotation(InvalidFormat.class);
                if (inv != null) {
                    res = invalidFormatAnnotationToErrorMessage(inv);
                }
            }
            return new ResultImpl<String>(res, formatArg);
        }

        /**
         * Returns the type of method invocation.
         *
         * @see InvocationType
         */
        public final Result<InvocationType> getInvocationType() {
            InvocationType type = InvocationType.VARARG;

            if (args.size() == 1) {
                final ExpressionTree first = args.get(0);
                TypeMirror argType = atypeFactory.getAnnotatedType(first).getUnderlyingType();
                // figure out if argType is an array
                type =
                        argType.accept(
                                new SimpleTypeVisitor7<InvocationType, Class<Void>>() {
                                    @Override
                                    protected InvocationType defaultAction(
                                            TypeMirror e, Class<Void> p) {
                                        // not an array
                                        return InvocationType.VARARG;
                                    }

                                    @Override
                                    public InvocationType visitArray(ArrayType t, Class<Void> p) {
                                        // it's an array, now figure out if it's a (Object[])null array
                                        return first.accept(
                                                new SimpleTreeVisitor<
                                                        InvocationType, Class<Void>>() {
                                                    @Override
                                                    protected InvocationType defaultAction(
                                                            Tree node, Class<Void> p) {
                                                        // just a normal array
                                                        return InvocationType.ARRAY;
                                                    }

                                                    @Override
                                                    public InvocationType visitTypeCast(
                                                            TypeCastTree node, Class<Void> p) {
                                                        // it's a (Object[])null
                                                        return atypeFactory
                                                                                .getAnnotatedType(
                                                                                        node
                                                                                                .getExpression())
                                                                                .getUnderlyingType()
                                                                                .getKind()
                                                                        == TypeKind.NULL
                                                                ? InvocationType.NULLARRAY
                                                                : InvocationType.ARRAY;
                                                    }
                                                },
                                                p);
                                    }

                                    @Override
                                    public InvocationType visitNull(NullType t, Class<Void> p) {
                                        return InvocationType.NULLARRAY;
                                    }
                                },
                                Void.TYPE);
            }

            ExpressionTree loc = node.getMethodSelect();
            if (type != InvocationType.VARARG && args.size() > 0) {
                loc = args.get(0);
            }
            return new ResultImpl<InvocationType>(type, loc);
        }

        /**
         * Returns the conversion category for every parameter.
         *
         * @see ConversionCategory
         */
        public final ConversionCategory[] getFormatCategories() {
            AnnotationMirror anno = formatAnno.getAnnotation(Format.class);
            return formatAnnotationToCategories(anno);
        }

        /**
         * Returns the type of the function's parameters.
         * Use {@link #isValidParameter(ConversionCategory, TypeMirror) isValidParameter}
         * and {@link #isParameterNull(TypeMirror) isParameterNull}
         * to work with the result.
         */
        public final Result<TypeMirror>[] getParamTypes() {
            // One to make javac happy, the other to make Eclipse happy...
            @SuppressWarnings({"rawtypes", "unchecked"})
            Result<TypeMirror>[] res = new Result[args.size()];
            for (int i = 0; i < res.length; ++i) {
                ExpressionTree arg = args.get(i);
                TypeMirror argType = atypeFactory.getAnnotatedType(arg).getUnderlyingType();
                res[i] = new ResultImpl<TypeMirror>(argType, arg);
            }
            return res;
        }

        /**
         * Checks if the type of a parameter returned from {@link #getParamTypes()}
         * is valid for the passed ConversionCategory.
         */
        public final boolean isValidParameter(ConversionCategory formatCat, TypeMirror paramType) {
            Class<? extends Object> type = typeMirrorToClass(paramType);
            if (type == null) {
                // we did not recognize the parameter type
                return false;
            }
            for (Class<? extends Object> c : formatCat.types) {
                if (c.isAssignableFrom(type)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Checks if the parameter returned from {@link #getParamTypes()} is a
         * {@code null} expression.
         */
        public final boolean isParameterNull(TypeMirror type) {
            // is it the null literal
            return type.accept(
                    new SimpleTypeVisitor7<Boolean, Class<Void>>() {
                        @Override
                        protected Boolean defaultAction(TypeMirror e, Class<Void> p) {
                            // it's not the null literal
                            return false;
                        }

                        @Override
                        public Boolean visitNull(NullType t, Class<Void> p) {
                            // it's the null literal
                            return true;
                        }
                    },
                    Void.TYPE);
        }
    }

    /**
     * Reports an error. Takes a {@link Result} to report the location.
     */
    public final <E> void failure(
            Result<E> res, /*@CompilerMessageKey*/ String msg, Object... args) {
        ResultImpl<E> impl = (ResultImpl<E>) res;
        checker.report(
                org.checkerframework.framework.source.Result.failure(msg, args), impl.location);
    }

    /**
     * Reports an warning. Takes a {@link Result} to report the location.
     */
    public final <E> void warning(
            Result<E> res, /*@CompilerMessageKey*/ String msg, Object... args) {
        ResultImpl<E> impl = (ResultImpl<E>) res;
        checker.report(
                org.checkerframework.framework.source.Result.warning(msg, args), impl.location);
    }

    /**
     * Takes an exception that describes an invalid formatter string and, returns a syntax trees
     * element that represents a {@link InvalidFormat} annotation with the exception's error
     * message as value.
     */
    public AnnotationMirror exceptionToInvalidFormatAnnotation(IllegalFormatException ex) {
        return stringToInvalidFormatAnnotation(ex.getMessage());
    }

    /**
     * Takes an invalid formatter string and, returns a syntax trees
     * element that represents a {@link InvalidFormat} annotation with
     * the invalid formatter string as value.
     */
    // package-private
    AnnotationMirror stringToInvalidFormatAnnotation(String invalidFormatString) {
        AnnotationBuilder builder =
                new AnnotationBuilder(processingEnv, InvalidFormat.class.getCanonicalName());
        builder.setValue("value", invalidFormatString);
        return builder.build();
    }

    /**
     * Takes a syntax tree element that represents a {@link InvalidFormat} annotation,
     * and returns its value.
     */
    public String invalidFormatAnnotationToErrorMessage(AnnotationMirror anno) {
        return "\"" + AnnotationUtils.getElementValue(anno, "value", String.class, true) + "\"";
    }

    /**
     * Takes a list of ConversionCategory elements, and returns a syntax tree
     * element that represents a {@link Format} annotation with the list as
     * value.
     */
    public AnnotationMirror categoriesToFormatAnnotation(ConversionCategory[] args) {
        AnnotationBuilder builder =
                new AnnotationBuilder(processingEnv, Format.class.getCanonicalName());
        builder.setValue("value", args);
        return builder.build();
    }

    /**
     * Takes a syntax tree element that represents a {@link Format} annotation,
     * and returns its value.
     */
    public ConversionCategory[] formatAnnotationToCategories(AnnotationMirror anno) {
        List<ConversionCategory> list =
                AnnotationUtils.getElementValueEnumArray(
                        anno, "value", ConversionCategory.class, false);
        return list.toArray(new ConversionCategory[] {});
    }

    private final Class<? extends Object> typeMirrorToClass(final TypeMirror type) {
        return type.accept(
                new SimpleTypeVisitor7<Class<? extends Object>, Class<Void>>() {
                    @Override
                    public Class<? extends Object> visitPrimitive(PrimitiveType t, Class<Void> v) {
                        switch (t.getKind()) {
                            case BOOLEAN:
                                return Boolean.class;
                            case BYTE:
                                return Byte.class;
                            case CHAR:
                                return Character.class;
                            case SHORT:
                                return Short.class;
                            case INT:
                                return Integer.class;
                            case LONG:
                                return Long.class;
                            case FLOAT:
                                return Float.class;
                            case DOUBLE:
                                return Double.class;
                            default:
                                return null;
                        }
                    }

                    @Override
                    public Class<? extends Object> visitDeclared(DeclaredType dt, Class<Void> v) {
                        return dt.asElement()
                                .accept(
                                        new SimpleElementVisitor7<
                                                Class<? extends Object>, Class<Void>>() {
                                            @Override
                                            public Class<? extends Object> visitType(
                                                    TypeElement e, Class<Void> v) {
                                                try {
                                                    return Class.forName(
                                                            e.getQualifiedName().toString());
                                                } catch (ClassNotFoundException e1) {
                                                    return null; // the lookup should work for all the classes we care about
                                                }
                                            }
                                        },
                                        Void.TYPE);
                    }
                },
                Void.TYPE);
    }
}
