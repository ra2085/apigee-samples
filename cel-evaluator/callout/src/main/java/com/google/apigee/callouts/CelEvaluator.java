// Copyright 2025 Google LLC.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.apigee.callouts;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;

import com.apigee.flow.execution.ExecutionContext;
import com.apigee.flow.execution.ExecutionResult;
import com.apigee.flow.execution.spi.Execution;
import com.apigee.flow.message.MessageContext;

import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelValidationException;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerBuilder;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeFactory;

public class CelEvaluator implements Execution {

  private static final String VAR_PREFIX = "cel_";
  private final Map<String, String> properties;
  
  private final CelRuntime celRuntime;
  private final CelCompiler celCompiler;
  private final CelRuntime.Program staticProgram;
  private final boolean isStaticConfig;

  public CelEvaluator(Map<String, String> properties) {
    this.properties = properties;
    this.celRuntime = CelRuntimeFactory.standardCelRuntimeBuilder().build();

    String inputVarsStr = properties.get("input-variables");
    String inputTemplatesStr = properties.get("input-templates");
    String expressionSpec = getRequiredProperty("expression");

    // Check if configuration is static (no Apigee {} variables in keys or lists)
    boolean varsStatic = (inputVarsStr == null || !inputVarsStr.contains("{"));
    
    boolean configStatic = true;
    CelCompilerBuilder compilerBuilder = CelCompilerFactory.standardCelCompilerBuilder();

    // Parse input-variables for static build
    if (inputVarsStr != null && !inputVarsStr.isEmpty()) {
       if (inputVarsStr.contains("{")) {
           configStatic = false;
       } else {
           for (String rawName : inputVarsStr.split(",")) {
               String varName = rawName.trim();
               if (!varName.isEmpty()) {
                   compilerBuilder.addVar(varName, SimpleType.DYN);
               }
           }
       }
    }

    // Parse input-templates for static build
    if (inputTemplatesStr != null && !inputTemplatesStr.isEmpty()) {
        String[] pairs = inputTemplatesStr.split(",");
        for (String pair : pairs) {
            String[] parts = pair.split(":", 2);
            if (parts.length == 2) {
                String key = parts[0].trim();
                if (key.contains("{")) {
                    configStatic = false;
                    break;
                }
                if (!key.isEmpty()) {
                    compilerBuilder.addVar(key, SimpleType.DYN);
                }
            } else {
                if (pair.contains("{")) configStatic = false;
            }
        }
    }

    if (configStatic) {
        this.celCompiler = compilerBuilder.build();
        this.isStaticConfig = true;
        
        if (!expressionSpec.contains("{")) {
            try {
                CelAbstractSyntaxTree ast = this.celCompiler.compile(expressionSpec).getAst();
                this.staticProgram = this.celRuntime.createProgram(ast);
            } catch (Exception e) {
                throw new IllegalArgumentException("Static CEL compilation failed: " + e.getMessage(), e);
            }
        } else {
            this.staticProgram = null;
        }
    } else {
        this.celCompiler = null; 
        this.isStaticConfig = false;
        this.staticProgram = null;
    }
  }

  private String getRequiredProperty(String name) {
    String value = properties.get(name);
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalStateException(String.format("'%s' property is required.", name));
    }
    return value.trim();
  }

  private String getOptionalProperty(String name, String defaultValue) {
    String value = properties.get(name);
    if (value == null || value.trim().isEmpty()) {
      return defaultValue;
    }
    return value.trim();
  }

  private String resolveVariable(String spec, MessageContext messageContext) {
    if (spec.startsWith("{") && spec.endsWith("}")) {
      String varName = spec.substring(1, spec.length() - 1);
      String value = messageContext.getVariable(varName);
      if (value == null) {
        throw new IllegalStateException(String.format("Context variable '%s' not found.", varName));
      }
      return value;
    }
    return spec;
  }

  private static String getStackTrace(Throwable t) {
    StringWriter sw = new StringWriter();
    PrintWriter pw = new PrintWriter(sw);
    t.printStackTrace(pw);
    return sw.toString();
  }

  private static final java.util.regex.Pattern INNERMOST_TEMPLATE_PATTERN = java.util.regex.Pattern.compile("\\{([^{}]+)\\}");

  private static String resolveTemplate(String template, MessageContext messageContext) {
    if (template == null) {
      return null;
    }

    String current = template;
    for (int i = 0; i < 10; i++) {
      java.util.regex.Matcher matcher = INNERMOST_TEMPLATE_PATTERN.matcher(current);
      if (!matcher.find()) {
        return current;
      }

      matcher.reset();
      StringBuffer sb = new StringBuffer();
      boolean changedInPass = false;

      while (matcher.find()) {
        String varName = matcher.group(1);
        Object varValue = messageContext.getVariable(varName);
        if (varValue != null) {
          matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(varValue.toString()));
          changedInPass = true;
        }
      }
      matcher.appendTail(sb);

      if (!changedInPass) {
        return current;
      }
      current = sb.toString();
    }
    return current;
  }

  private static final java.util.concurrent.ConcurrentHashMap<String, CelRuntime.Program> PROGRAM_CACHE = 
      new java.util.concurrent.ConcurrentHashMap<>();
  private static final int MAX_CACHE_SIZE = 1000;

  public ExecutionResult execute(MessageContext messageContext, ExecutionContext executionContext) {
    try {
      String outputVariable = getOptionalProperty("output-variable", "cel_result");
      
      Map<String, Object> inputValues = new HashMap<>();
      
      String inputVarsStr = getOptionalProperty("input-variables", "");
      if (!inputVarsStr.isEmpty()) {
           String[] varNames = inputVarsStr.split(",");
           for (String rawName : varNames) {
              String name = rawName.trim();
              if (isStaticConfig) {
                  if (!name.isEmpty()) inputValues.put(name, messageContext.getVariable(name));
              } else {
                   String resolvedName = resolveVariable(name, messageContext);
                   if (!resolvedName.isEmpty()) {
                       inputValues.put(resolvedName, messageContext.getVariable(resolvedName));
                   }
              }
           }
      }

      String inputTemplatesStr = getOptionalProperty("input-templates", "");
      if (!inputTemplatesStr.isEmpty()) {
          String[] pairs = inputTemplatesStr.split(",");
          for (String pair : pairs) {
              String[] parts = pair.split(":", 2);
              if (parts.length == 2) {
                  String key = parts[0].trim();
                  String template = parts[1].trim();
                  if (!isStaticConfig) {
                      key = resolveVariable(key, messageContext);
                  }
                  if (!key.isEmpty()) {
                      inputValues.put(key, resolveTemplate(template, messageContext));
                  }
              }
          }
      }

      CelRuntime.Program program = this.staticProgram;
      
      if (program == null) {
          // Dynamic path - determine expression and environment
          String expressionSpec = getRequiredProperty("expression");
          // If we had a static config compiler but failed static checks (shouldn't happen with current logic),
          // or if it's dynamic config.
          String expression = resolveVariable(expressionSpec, messageContext);
          
          // Construct cache key: expression + "::" + sorted_var_names
          // For stable keys, we simply use the set of keys in inputValues.
          java.util.TreeSet<String> sortedKeys = new java.util.TreeSet<>(inputValues.keySet());
          String cacheKey = expression + "::" + String.join(",", sortedKeys);
          
          program = PROGRAM_CACHE.computeIfAbsent(cacheKey, k -> {
              if (PROGRAM_CACHE.size() >= MAX_CACHE_SIZE) {
                  PROGRAM_CACHE.clear(); // Simple eviction: clear all if full to avoid OOM
              }
              
              CelCompilerBuilder builder = CelCompilerFactory.standardCelCompilerBuilder();
              for (String key : sortedKeys) {
                  builder.addVar(key, SimpleType.DYN);
              }
              CelCompiler compiler = builder.build();
              try {
                  CelAbstractSyntaxTree ast = compiler.compile(expression).getAst();
                  return this.celRuntime.createProgram(ast); // Shared runtime is fine (thread-safe)
              } catch (Exception e) {
                  // We can't easily throw checked exceptions from computeIfAbsent without wrapping
                  throw new RuntimeException("CEL compilation failed: " + e.getMessage(), e);
              }
          });
      }
      
      Object result = program.eval(inputValues);
      messageContext.setVariable(outputVariable, result);

      return ExecutionResult.SUCCESS;
    } catch (Exception e) {
      messageContext.setVariable(VAR_PREFIX + "error", e.getMessage());
      messageContext.setVariable(VAR_PREFIX + "stacktrace", getStackTrace(e));
      return ExecutionResult.ABORT;
    }
  }
}
