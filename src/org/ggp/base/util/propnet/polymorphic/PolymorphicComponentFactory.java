package org.ggp.base.util.propnet.polymorphic;

import org.ggp.base.util.gdl.grammar.GdlSentence;

public abstract class PolymorphicComponentFactory {
	public abstract PolymorphicAnd createAnd(int numInputs, int numOutputs);
	public abstract PolymorphicOr createOr(int numInputs, int numOutputs);
	public abstract PolymorphicNot createNot(int numOutputs);
	public abstract PolymorphicConstant createConstant(int numOutputs, boolean value);
	public abstract PolymorphicProposition createProposition(int numOutputs, GdlSentence name);
	public abstract PolymorphicTransition createTransition(int numOutputs);
}
