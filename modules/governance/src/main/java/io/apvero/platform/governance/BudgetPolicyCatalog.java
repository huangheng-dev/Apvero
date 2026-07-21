package io.apvero.platform.governance;

import java.util.List;
import java.util.UUID;

public interface BudgetPolicyCatalog {
    List<BudgetPolicy> listBudgets(UUID workspaceId);

    BudgetPolicy create(UUID workspaceId, CreateBudgetPolicyCommand command);
}
