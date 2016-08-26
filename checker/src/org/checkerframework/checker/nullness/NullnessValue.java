package org.checkerframework.checker.nullness;

import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.type.TypeMirror;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.nullness.qual.PolyNull;
import org.checkerframework.framework.flow.CFAbstractAnalysis;
import org.checkerframework.framework.flow.CFAbstractValue;
import org.checkerframework.framework.flow.CFValue;
import org.checkerframework.framework.type.AnnotatedTypeMirror;

/**
 * Behaves just like {@link CFValue}, but additionally tracks whether at this
 * point {@link PolyNull} is known to be {@link Nullable}.
 *
 * @author Stefan Heule
 */
public class NullnessValue extends CFAbstractValue<NullnessValue> {

    protected boolean isPolyNullNull;

    public NullnessValue(
            CFAbstractAnalysis<NullnessValue, ?, ?> analysis,
            Set<AnnotationMirror> annotations,
            TypeMirror underlyingType) {
        super(analysis, annotations, underlyingType);
    }

    public NullnessValue(
            CFAbstractAnalysis<NullnessValue, ?, ?> analysis, AnnotatedTypeMirror type) {
        super(analysis, type);
    }
}
