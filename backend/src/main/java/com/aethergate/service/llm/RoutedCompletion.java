package com.aethergate.service.llm;

import java.util.List;

/** The provider that finally answered, what it produced, and the path taken to get there. */
public record RoutedCompletion(RouteTarget target, ProviderResult result, boolean failoverTriggered, boolean live,
                               List<String> attempts) {}
