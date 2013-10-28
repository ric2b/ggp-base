package org.ggp.base.util.propnet.polymorphic;

import org.ggp.base.util.gdl.grammar.GdlSentence;

public abstract class PolymorphicComponentFactory {
	public abstract PolymorphicAnd createAnd();
	public abstract PolymorphicOr createOr();
	public abstract PolymorphicNot createNot();
	public abstract PolymorphicConstant createConstant(boolean value);
	public abstract PolymorphicProposition createProposition(GdlSentence name);
	public abstract PolymorphicTransition createTransition();
}
