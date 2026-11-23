package com.aethergate.service.metering;

import java.math.BigDecimal;

/** 402: the request would push its project past the project's monthly budget (tenant budget may still have room). */
public class ProjectBudgetExceededException extends RuntimeException {

    private final String projectName;
    private final BigDecimal budget;
    private final BigDecimal projected;

    public ProjectBudgetExceededException(String projectName, BigDecimal budget, BigDecimal projected) {
        super("Project '" + projectName + "' monthly budget of $" + budget.stripTrailingZeros().toPlainString()
                + " would be exceeded (projected $" + projected.toPlainString() + ")");
        this.projectName = projectName;
        this.budget = budget;
        this.projected = projected;
    }

    public String getProjectName() { return projectName; }
    public BigDecimal getBudget() { return budget; }
    public BigDecimal getProjected() { return projected; }
}
