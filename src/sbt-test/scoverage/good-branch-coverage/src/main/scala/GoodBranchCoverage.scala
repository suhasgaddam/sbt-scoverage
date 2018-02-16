object GoodBranchCoverage {

  def and(a: Boolean, b: Boolean) = {
    if (a)
      if (b)
        true
      else
        false
    else
      false
  }

  def or(a: Boolean, b: Boolean) = {
    if (a)
      true
    else if (b)
      true
    else
      false
  }
}