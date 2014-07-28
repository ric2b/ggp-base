
package org.ggp.base.util.propnet.polymorphic.learning;

import java.util.Set;

import org.ggp.base.util.gdl.grammar.GdlSentence;
import org.ggp.base.util.propnet.architecture.PropNet;
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

public class LearningComponentFactory extends PolymorphicComponentFactory
{

  public LearningComponentFactory()
  {
  }

  @Override
  public PolymorphicPropNet createPropNet(PropNet basicPropNet)
  {
    return new PolymorphicPropNet(basicPropNet, this);
  }

  @Override
  public PolymorphicPropNet createPropNet(PolymorphicPropNet propNet)
  {
    return new PolymorphicPropNet(propNet, this);
  }

  @Override
  public PolymorphicPropNet createPropNet(Role[] roles,
                                          Set<PolymorphicComponent> components)
  {
    return new PolymorphicPropNet(roles, components, this);
  }

  @Override
  public PolymorphicAnd createAnd(int numInputs, int numOutputs)
  {
    return new LearningAnd();
  }

  @Override
  public PolymorphicOr createOr(int numInputs, int numOutputs)
  {
    return new LearningOr();
  }

  @Override
  public PolymorphicNot createNot(int numOutputs)
  {
    return new LearningNot();
  }

  @Override
  public PolymorphicConstant createConstant(int numOutputs, boolean value)
  {
    return new LearningConstant(value);
  }

  @Override
  public PolymorphicProposition createProposition(int numOutputs,
                                                  GdlSentence name)
  {
    return new LearningProposition(name);
  }

  @Override
  public PolymorphicTransition createTransition(int numOutputs)
  {
    return new LearningTransition();
  }
}
