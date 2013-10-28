package org.ggp.base.util.propnet.polymorphic.runtimeOptimized;

import org.ggp.base.util.gdl.grammar.GdlSentence;
import org.ggp.base.util.propnet.polymorphic.PolymorphicAnd;
import org.ggp.base.util.propnet.polymorphic.PolymorphicComponentFactory;
import org.ggp.base.util.propnet.polymorphic.PolymorphicConstant;
import org.ggp.base.util.propnet.polymorphic.PolymorphicNot;
import org.ggp.base.util.propnet.polymorphic.PolymorphicOr;
import org.ggp.base.util.propnet.polymorphic.PolymorphicProposition;
import org.ggp.base.util.propnet.polymorphic.PolymorphicTransition;

public class RuntimeOptimizedComponentFactory extends PolymorphicComponentFactory {

	public RuntimeOptimizedComponentFactory()
	{
	}
	
	@Override
	public PolymorphicAnd createAnd() {
		return new RuntimeOptimizedAnd();
	}
	
	@Override
	public PolymorphicOr createOr() {
		return new RuntimeOptimizedOr();
	}
	
	@Override
	public PolymorphicNot createNot() {
		return new RuntimeOptimizedNot();
	}

	@Override
	public PolymorphicConstant createConstant(boolean value)
	{
		return new RuntimeOptimizedConstant(value);
	}
	
	@Override
	public PolymorphicProposition createProposition(GdlSentence name)
	{
		return new RuntimeOptimizedProposition(name);
	}

	@Override
	public PolymorphicTransition createTransition()
	{
		return new RuntimeOptimizedTransition();
	}
}
