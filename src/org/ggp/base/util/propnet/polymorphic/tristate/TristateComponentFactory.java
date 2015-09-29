package org.ggp.base.util.propnet.polymorphic.tristate;

import java.util.Set;

import org.ggp.base.util.gdl.grammar.GdlSentence;
import org.ggp.base.util.propnet.polymorphic.PolymorphicAnd;
import org.ggp.base.util.propnet.polymorphic.PolymorphicComponent;
import org.ggp.base.util.propnet.polymorphic.PolymorphicComponentFactory;
import org.ggp.base.util.propnet.polymorphic.PolymorphicConstant;
import org.ggp.base.util.propnet.polymorphic.PolymorphicNot;
import org.ggp.base.util.propnet.polymorphic.PolymorphicOr;
import org.ggp.base.util.propnet.polymorphic.PolymorphicPropNet;
import org.ggp.base.util.propnet.polymorphic.PolymorphicProposition;
import org.ggp.base.util.propnet.polymorphic.PolymorphicTransition;
import org.ggp.base.util.statemachine.Role;

public class TristateComponentFactory extends PolymorphicComponentFactory
{

  @Override
  public PolymorphicPropNet createPropNet(Role[] xiRoles,
                                          Set<PolymorphicComponent> xiComponents)
  {
    throw new RuntimeException("Not implemented");
  }

  @Override
  public PolymorphicAnd createAnd(int xiNumInputs, int xiNumOutputs)
  {
    return new TristateAnd();
  }

  @Override
  public PolymorphicOr createOr(int xiNumInputs, int xiNumOutputs)
  {
    return new TristateOr();
  }

  @Override
  public PolymorphicNot createNot(int xiNumOutputs)
  {
    return new TristateNot();
  }

  @Override
  public PolymorphicConstant createConstant(int xiNumOutputs, boolean xiValue)
  {
    return new TristateConstant(xiValue);
  }

  @Override
  public PolymorphicProposition createProposition(int xiNumOutputs, GdlSentence xiName)
  {
    return new TristateProposition();
  }

  @Override
  public PolymorphicTransition createTransition(int xiNumOutputs)
  {
    return new TristateTransition();
  }
}
