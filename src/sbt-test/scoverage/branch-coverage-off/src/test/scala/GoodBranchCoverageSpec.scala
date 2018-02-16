import org.specs2.mutable._

/**
  * Created by tbarke001c on 7/8/14.
  */
class GoodBranchCoverageSpec extends Specification {

  "GoodBranchCoverage" should {
    "true AND true == true" in {
      GoodBranchCoverage.and(true, true) mustEqual true
    }

    "true AND false == false" in {
      GoodBranchCoverage.and(true, false) mustEqual false
    }

    "false AND true == false" in {
      GoodBranchCoverage.and(false, true) mustEqual false
    }

    "false AND false == false" in {
      GoodBranchCoverage.and(false, false) mustEqual false
    }

    "true OR true == true" in {
      GoodBranchCoverage.or(true, true) mustEqual true
    }

    "true OR false == true" in {
      GoodBranchCoverage.or(true, false) mustEqual true
    }

    "false OR true == true" in {
      GoodBranchCoverage.or(false, true) mustEqual true
    }
  }
}
