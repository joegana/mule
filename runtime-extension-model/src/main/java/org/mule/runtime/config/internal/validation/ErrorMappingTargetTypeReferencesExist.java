/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.config.internal.validation;

import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.stream.Collectors.toSet;
import static org.mule.runtime.api.component.ComponentIdentifier.builder;
import static org.mule.runtime.ast.api.util.ComponentAstPredicatesFactory.currentElemement;
import static org.mule.runtime.ast.api.validation.Validation.Level.ERROR;
import static org.mule.runtime.internal.dsl.DslConstants.CORE_PREFIX;

import org.mule.runtime.api.component.ComponentIdentifier;
import org.mule.runtime.api.message.ErrorType;
import org.mule.runtime.ast.api.ArtifactAst;
import org.mule.runtime.ast.api.ComponentAst;
import org.mule.runtime.ast.api.ComponentParameterAst;
import org.mule.runtime.ast.api.validation.Validation;
import org.mule.runtime.extension.api.error.ErrorMapping;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Referenced error types do exist in the context of the artifact
 */
public class ErrorMappingTargetTypeReferencesExist implements Validation {

  private static final String RAISE_ERROR = "raise-error";

  private static final ComponentIdentifier RAISE_ERROR_IDENTIFIER =
      builder().namespace(CORE_PREFIX).name(RAISE_ERROR).build();

  @Override
  public String getName() {
    return "Error Type references exist";
  }

  @Override
  public String getDescription() {
    return "Referenced error types do exist in the context of the artifact.";
  }

  @Override
  public Level getLevel() {
    return ERROR;
  }

  @Override
  public Predicate<List<ComponentAst>> applicable() {
    return currentElemement(comp -> {
      final ComponentParameterAst errorMappingsAst = comp.getParameter("errorMappings");
      if (errorMappingsAst == null) {
        return false;
      }
      return errorMappingsAst != null && !((List<ErrorMapping>) errorMappingsAst.getValue().getRight()).isEmpty();
    });
  }

  @Override
  public Optional<String> validate(ComponentAst component, ArtifactAst artifact) {
    List<ErrorMapping> errorMappings = (List<ErrorMapping>) component.getParameter("errorMappings").getValue().getRight();

    final Set<String> errorNamespaces = artifact.dependencies().stream()
        .map(d -> d.getXmlDslModel().getPrefix().toUpperCase())
        .collect(toSet());

    for (ErrorMapping errorMapping : errorMappings) {
      final ComponentIdentifier errorTypeId = parserErrorType(errorMapping.getTarget());

      if (errorNamespaces.contains(errorTypeId.getNamespace())) {
        final Optional<ErrorType> errorType = artifact.getErrorTypeRepository().lookupErrorType(errorTypeId);

        if (!errorType.isPresent()) {
          return of("Could not find error '" + errorMapping.getTarget() + "' used in " + compToLoc(component));
        }

      }
    }

    return empty();
  }

  private static ComponentIdentifier parserErrorType(String representation) {
    int separator = representation.indexOf(':');
    String namespace;
    String identifier;
    if (separator > 0) {
      namespace = representation.substring(0, separator).toUpperCase();
      identifier = representation.substring(separator + 1).toUpperCase();
    } else {
      namespace = CORE_PREFIX.toUpperCase();
      identifier = representation.toUpperCase();
    }

    return builder().name(identifier).namespace(namespace).build();
  }

  private String compToLoc(ComponentAst component) {
    return "[" + component.getMetadata().getFileName().orElse("unknown") + ":"
        + component.getMetadata().getStartLine().orElse(-1) + "]";
  }

}
