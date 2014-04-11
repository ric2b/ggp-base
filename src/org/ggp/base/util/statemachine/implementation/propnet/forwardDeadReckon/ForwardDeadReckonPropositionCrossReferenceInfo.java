package org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon;

import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonProposition;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonPropositionInfo;

public class ForwardDeadReckonPropositionCrossReferenceInfo extends
                                                           ForwardDeadReckonPropositionInfo
{
  public ForwardDeadReckonProposition xNetProp;
  public ForwardDeadReckonProposition oNetProp;
  public ForwardDeadReckonProposition goalsNetProp;
  public Factor                       factor = null;
}
