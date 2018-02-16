import org.specs2.mutable._

/**
 * Created by tbarke001c on 7/8/14.
 */
class BadBranchCoverageSpec extends Specification {

  "BadBranchCoverage" should {
    "true AND true == true" in {
      BadBranchCoverage.and(true, true) mustEqual true
    }

    "true OR true == true" in {
      BadBranchCoverage.or(true, true) mustEqual true
    }
  }
}
