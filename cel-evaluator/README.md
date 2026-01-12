# CEL Evaluator Java Callout

This example provides an Apigee Proxy that uses a custom [Java Callout](https://cloud.google.com/apigee/docs/api-platform/reference/policies/java-callout-policy) to evaluate [Common Expression Language (CEL)](https://github.com/google/cel-spec) expressions.

## About the CEL Evaluator
The CEL Evaluator Callout allows you to execute dynamic, safe, and expression-based logic within your API proxy flow. Unlike JavaScript or Python policies, CEL is fast, and side-effect free, making it ideal for complex validation, authorization rules, routing logic, and data transformation policies.

## Features
- **Dynamic Evaluation**: Evaluate arbitrary CEL expressions at runtime.
- **Variable Binding**: Bind Apigee flow variables directly to CEL variables.
- **Template Support**: Resolve message templates (e.g., "Hello {user.name}") and bind them to CEL variables.
- **High Performance**: 
    - **Static Optimization**: Pre-compiles expressions that do not change.
    - **Global Caching**: Caches compiled programs for dynamic expressions using a thread-safe global cache.

## Prerequisites
1. [Provision Apigee X](https://cloud.google.com/apigee/docs/api-platform/get-started/provisioning-intro)
2. Configure [external access](https://cloud.google.com/apigee/docs/api-platform/get-started/configure-routing#external-access) for API traffic to your Apigee X instance.
3. A Linux-based shell with:
    * [gcloud CLI](https://cloud.google.com/sdk/docs/install)
    * unzip
    * curl
    * jq
    * npm
    * maven (>= 3.9.0)
    * java (>= 11)

## (QuickStart) Setup using CloudShell

Use the following GCP CloudShell tutorial, and follow the instructions.

[![Open in Cloud Shell](https://gstatic.com/cloudssh/images/open-btn.svg)](https://ssh.cloud.google.com/cloudshell/open?cloudshell_git_repo=https://github.com/GoogleCloudPlatform/apigee-samples&cloudshell_git_branch=main&cloudshell_workspace=.&cloudshell_tutorial=cel-evaluator/docs/cloudshell-tutorial.md)

## Setup Instructions

1. Clone the apigee-samples repo, and switch to the cel-evaluator directory
   ```bash
   git clone https://github.com/GoogleCloudPlatform/apigee-samples.git
   cd apigee-samples/cel-evaluator
   ```

2. Edit `env.sh` and configure your environment:
   * `PROJECT`: Your Google Cloud Project ID.
   * `APIGEE_ENV`: Your Apigee Environment name.
   * `APIGEE_HOST`: The external hostname for your Apigee environment.

   Source the environment variables:
   ```bash
   source ./env.sh
   ```

3. Deploy the Sample:
   This script will download dependencies, build the Java Callout (Uber-Jar), and deploy the proxy.
   ```bash
   ./deploy-cel-evaluator.sh
   ```

## Test the API
Make a call to the deployed proxy:

```bash
curl -v "https://$APIGEE_HOST/v1/samples/cel-evaluator-example?name=ApigeeUser"
```

### Expected Response
```json
{
    "success": true,
    "cel_result": true,
    "greeting": "Hello ApigeeUser",
    "user_agent_from_cel": "curl/7.88.1"
}
```

## Configuration Reference
The policy is configured in `apiproxy/policies/JC-Evaluate-CEL.xml`:

```xml
<JavaCallout name="JC-Evaluate-CEL">
    <ClassName>com.google.apigee.callouts.CelEvaluator</ClassName>
    <Properties>
        <Property name="input-variables">user_agent:request.header.User-Agent</Property>
        <Property name="input-templates">greeting:Hello {request.queryparam.name}</Property>
        <Property name="expression">
            size(user_agent) > 0 && greeting.startsWith("Hello")
        </Property>
        <Property name="output-variable">cel_result</Property>
    </Properties>
    <ResourceURL>java://cel-evaluator.jar</ResourceURL>
</JavaCallout>
```

## Cleanup
To remove the deployed artifacts:

```bash
source ./env.sh
./clean-up-cel-evaluator.sh
```
