package org.ggp.base.player.gamer.statemachine.sancho;

/**
 * @author steve
 * To avoid individual allocation of small score vectors in every node
 * we allocate via one large pooled array with logical access to
 * per-role values for average and average squared score
 */
public class ScoreVectorPool
{
  private final double[]  scoreArray;
  private final int       numRoles;
  private final int       instanceSize;

  /**
   * Construct pooled score vector
   * @param numInstances  Number of separate uses (maps directly to nodes currently)
   * @param numGameRoles  Number of roles in the game
   */
  public ScoreVectorPool(int numInstances, int numGameRoles)
  {
    numRoles = numGameRoles;
    instanceSize = numRoles*2;  //  average and squared scores
    scoreArray = new double[numInstances*instanceSize];
  }

  /**
   * Set an average score value
   * @param instanceId  Instance to set the member value for
   * @param roleIndex   Role value pertains to
   * @param value       Value to set
   */
  public void setAverageScore(int instanceId, int roleIndex, double value)
  {
    scoreArray[instanceId*instanceSize + roleIndex] = value;
  }

  /**
   * Get an average score value
   * @param instanceId  Instance to get the member value for
   * @param roleIndex   Role value pertains to
   * @return average score for the specified role in the specified instance
   */
  public double getAverageScore(int instanceId, int roleIndex)
  {
    return scoreArray[instanceId*instanceSize + roleIndex];
  }

  /**
   * Set an average squared score value
   * @param instanceId  Instance to set the member value for
   * @param roleIndex   Role value pertains to
   * @param value       Value to set
   */
  public void setAverageSquaredScore(int instanceId, int roleIndex, double value)
  {
    scoreArray[instanceId*instanceSize + roleIndex + numRoles] = value;
  }

  /**
   * Get an average squared score value
   * @param instanceId  Instance to get the member value for
   * @param roleIndex   Role value pertains to
   * @return average squared score for the specified role in the specified instance
   */
  public double getAverageSquaredScore(int instanceId, int roleIndex)
  {
    return scoreArray[instanceId*instanceSize + roleIndex + numRoles];
  }
}
