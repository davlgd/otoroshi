import React, { useEffect, useState } from "react";
import { TextInput } from "../../components/inputs";
import {
  getOldPlugins,
  getPlugins,
  nextClient,
} from "../../services/BackOfficeServices";
import { Plugins } from "../../forms/ng_plugins";
import { Button } from "../../components/Button";

// Icons as SVG components
const Icons = {
  route: (
    <svg
      width="24"
      height="24"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
    >
      <circle cx="6" cy="19" r="3" />
      <path d="M9 19h8.5a3.5 3.5 0 0 0 0-7h-11a3.5 3.5 0 0 1 0-7H15" />
      <circle cx="18" cy="5" r="3" />
    </svg>
  ),
  globe: (
    <svg
      width="24"
      height="24"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
    >
      <circle cx="12" cy="12" r="10" />
      <line x1="2" y1="12" x2="22" y2="12" />
      <path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z" />
    </svg>
  ),
  server: (
    <svg
      width="24"
      height="24"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
    >
      <rect x="2" y="2" width="20" height="8" rx="2" ry="2" />
      <rect x="2" y="14" width="20" height="8" rx="2" ry="2" />
      <line x1="6" y1="6" x2="6.01" y2="6" />
      <line x1="6" y1="18" x2="6.01" y2="18" />
    </svg>
  ),
  layers: (
    <svg
      width="24"
      height="24"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
    >
      <polygon points="12 2 2 7 12 12 22 7 12 2" />
      <polyline points="2 17 12 22 22 17" />
      <polyline points="2 12 12 17 22 12" />
    </svg>
  ),
  check: (
    <svg
      width="24"
      height="24"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
    >
      <polyline points="20 6 9 17 4 12" />
    </svg>
  ),
  chevronRight: (
    <svg
      width="20"
      height="20"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
    >
      <polyline points="9 18 15 12 9 6" />
    </svg>
  ),
  chevronLeft: (
    <svg
      width="20"
      height="20"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
    >
      <polyline points="15 18 9 12 15 6" />
    </svg>
  ),
  close: (
    <svg
      width="24"
      height="24"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
    >
      <line x1="18" y1="6" x2="6" y2="18" />
      <line x1="6" y1="6" x2="18" y2="18" />
    </svg>
  ),
  sparkles: (
    <svg
      width="24"
      height="24"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
    >
      <path d="M12 3l1.5 4.5L18 9l-4.5 1.5L12 15l-1.5-4.5L6 9l4.5-1.5L12 3z" />
      <path d="M5 19l1 3 1-3 3-1-3-1-1-3-1 3-3 1 3 1z" />
      <path d="M19 13l1 2 1-2 2-1-2-1-1-2-1 2-2 1 2 1z" />
    </svg>
  ),
  code: (
    <svg
      width="24"
      height="24"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
    >
      <polyline points="16 18 22 12 16 6" />
      <polyline points="8 6 2 12 8 18" />
    </svg>
  ),
  layout: (
    <svg
      width="24"
      height="24"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
    >
      <rect x="3" y="3" width="18" height="18" rx="2" ry="2" />
      <line x1="3" y1="9" x2="21" y2="9" />
      <line x1="9" y1="21" x2="9" y2="9" />
    </svg>
  ),
  database: (
    <svg
      width="24"
      height="24"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
    >
      <ellipse cx="12" cy="5" rx="9" ry="3" />
      <path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3" />
      <path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5" />
    </svg>
  ),
  zap: (
    <svg
      width="24"
      height="24"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
    >
      <polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2" />
    </svg>
  ),
  box: (
    <svg
      width="24"
      height="24"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
    >
      <path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z" />
      <polyline points="3.27 6.96 12 12.01 20.73 6.96" />
      <line x1="12" y1="22.08" x2="12" y2="12" />
    </svg>
  ),
};

// Route templates configuration
const ROUTE_TEMPLATES = [
  {
    kind: "api",
    title: "REST API",
    description:
      "Secured REST API with API management, authentication and rate limiting",
    icon: Icons.code,
    color: "#3b82f6",
    features: ["API Keys", "Rate Limiting", "CORS", "HTTPS"],
  },
  {
    kind: "webapp",
    title: "Web Application",
    description:
      "Web application with authentication module and session management",
    icon: Icons.layout,
    color: "#8b5cf6",
    features: ["Auth Module", "Sessions", "HTTPS", "Compression"],
  },
  {
    kind: "graphql-proxy",
    title: "GraphQL API",
    description: "GraphQL API proxy with validation and introspection",
    icon: Icons.database,
    color: "#ec4899",
    features: ["GraphQL Proxy", "Validation", "Introspection"],
  },
  {
    kind: "mock",
    title: "Mock API",
    description: "Quickly create mock endpoints for development and testing",
    icon: Icons.zap,
    color: "#f59e0b",
    features: ["Mock Responses", "No Backend", "Quick Setup"],
  },
  {
    kind: "empty",
    title: "Blank Route",
    description: "Start from scratch with no pre-configured plugins",
    icon: Icons.box,
    color: "#71717a",
    features: ["Clean Slate", "Full Control"],
  },
];

// Step 1: Route Name
const StepName = ({ state, onChange }) => (
  <div className="wizard-step">
    <div className="wizard-step__header">
      <div className="wizard-step__icon" style={{ "--icon-color": "#f59e0b" }}>
        {Icons.route}
      </div>
      <div className="wizard-step__intro">
        <h2 className="wizard-step__title">Name your route</h2>
        <p className="wizard-step__subtitle">
          Choose a descriptive name that will help you identify this route later
        </p>
      </div>
    </div>

    <div className="wizard-step__content">
      <div className="wizard-input-group">
        <label className="wizard-input-label">Route name</label>
        <input
          type="text"
          className="wizard-input wizard-input--large"
          placeholder="e.g., My API Gateway, Customer Portal..."
          value={state.route.name}
          onChange={(e) => onChange(e.target.value)}
          autoFocus
        />
        <span className="wizard-input-hint">
          This name will appear in the routes list and logs
        </span>
      </div>
    </div>
  </div>
);

// Step 2: Template Selection
const StepTemplate = ({ state, onChange }) => (
  <div className="wizard-step">
    <div className="wizard-step__header">
      <div className="wizard-step__icon" style={{ "--icon-color": "#8b5cf6" }}>
        {Icons.layers}
      </div>
      <div className="wizard-step__intro">
        <h2 className="wizard-step__title">Choose a template</h2>
        <p className="wizard-step__subtitle">
          Select a pre-configured template or start from scratch
        </p>
      </div>
    </div>

    <div className="wizard-step__content">
      <div className="wizard-templates">
        {ROUTE_TEMPLATES.map((template) => (
          <button
            key={template.kind}
            type="button"
            className={`wizard-template ${
              state.route.kind === template.kind
                ? "wizard-template--selected"
                : ""
            }`}
            onClick={() => onChange(template.kind)}
            style={{ "--template-color": template.color }}
          >
            <div className="wizard-template__icon">{template.icon}</div>
            <div className="wizard-template__content">
              <h3 className="wizard-template__title">{template.title}</h3>
              <p className="wizard-template__description">
                {template.description}
              </p>
              <div className="wizard-template__features">
                {template.features.map((feature) => (
                  <span key={feature} className="wizard-template__feature">
                    {feature}
                  </span>
                ))}
              </div>
            </div>
            <div className="wizard-template__check">
              {state.route.kind === template.kind && Icons.check}
            </div>
          </button>
        ))}
      </div>
    </div>
  </div>
);

// Step 3: Frontend Configuration
const StepFrontend = ({ state, onChange, env }) => {
  const defaultDomain = env?.routeBaseDomain || "oto.tools";
  const suggestedDomain = `${state.route.name
    .toLowerCase()
    .replace(/[^a-z0-9]/g, "-")}.${defaultDomain}`;

  return (
    <div className="wizard-step">
      <div className="wizard-step__header">
        <div
          className="wizard-step__icon"
          style={{ "--icon-color": "#10b981" }}
        >
          {Icons.globe}
        </div>
        <div className="wizard-step__intro">
          <h2 className="wizard-step__title">Configure the frontend</h2>
          <p className="wizard-step__subtitle">
            Define how users will access your service
          </p>
        </div>
      </div>

      <div className="wizard-step__content">
        <div className="wizard-input-group">
          <label className="wizard-input-label">Domain name</label>
          <input
            type="text"
            className="wizard-input"
            placeholder={`e.g., api.${defaultDomain}`}
            value={state.route.domain}
            onChange={(e) => onChange(e.target.value)}
            autoFocus
          />
          <span className="wizard-input-hint">
            The domain where your service will be accessible
          </span>
        </div>

        {!state.route.domain && (
          <button
            type="button"
            className="wizard-suggestion"
            onClick={() => onChange(suggestedDomain)}
          >
            <span className="wizard-suggestion__icon">{Icons.sparkles}</span>
            <span>
              Use suggested: <strong>{suggestedDomain}</strong>
            </span>
          </button>
        )}
      </div>
    </div>
  );
};

// Step 4: Backend Configuration
const StepBackend = ({ state, onChange, onError, error, env }) => {
  const defaultBackend = env?.defaultBackendTls
    ? `https://${env?.defaultBackendHost || "request.otoroshi.io"}:${
        env?.defaultBackendPort || 443
      }`
    : `http://${env?.defaultBackendHost || "request.otoroshi.io"}:${
        env?.defaultBackendPort || 80
      }`;

  const validateUrl = (url) => {
    try {
      if (!url) {
        onError(false);
        return;
      }
      if (!url.includes("://")) {
        onError("URL must include protocol (http:// or https://)");
        return;
      }
      new URL(url);
      onError(false);
    } catch (err) {
      onError("Invalid URL format");
    }
  };

  const handleChange = (value) => {
    onChange(value);
    validateUrl(value);
  };

  // Skip this step for mock/graphql routes
  if (["mock", "graphql"].includes(state.route.kind)) {
    return null;
  }

  return (
    <div className="wizard-step">
      <div className="wizard-step__header">
        <div
          className="wizard-step__icon"
          style={{ "--icon-color": "#06b6d4" }}
        >
          {Icons.server}
        </div>
        <div className="wizard-step__intro">
          <h2 className="wizard-step__title">Configure the backend</h2>
          <p className="wizard-step__subtitle">
            Define where traffic should be redirected
          </p>
        </div>
      </div>

      <div className="wizard-step__content">
        <div className="wizard-input-group">
          <label className="wizard-input-label">
            {state.route.kind === "graphql-proxy"
              ? "GraphQL Endpoint"
              : "Target URL"}
          </label>
          <input
            type="text"
            className={`wizard-input ${error ? "wizard-input--error" : ""}`}
            placeholder="https://backend.example.com"
            value={state.route.url}
            onChange={(e) => handleChange(e.target.value)}
            autoFocus
          />
          {error && <span className="wizard-input-error">{error}</span>}
          {!error && (
            <span className="wizard-input-hint">
              The backend server that will handle requests
            </span>
          )}
        </div>

        {!state.route.url && (
          <button
            type="button"
            className="wizard-suggestion"
            onClick={() => handleChange(defaultBackend)}
          >
            <span className="wizard-suggestion__icon">{Icons.sparkles}</span>
            <span>
              Use default: <strong>{defaultBackend}</strong>
            </span>
          </button>
        )}
      </div>
    </div>
  );
};

// Step 5: Summary & Creation
const StepSummary = ({ state, env, onBack }) => {
  const [status, setStatus] = useState("idle"); // idle, creating, success, error
  const [createdRoute, setCreatedRoute] = useState(null);
  const [errorMessage, setErrorMessage] = useState("");

  const template = ROUTE_TEMPLATES.find((t) => t.kind === state.route.kind);

  const API_PLUGINS = [
    "cp:otoroshi.next.plugins.ForceHttpsTraffic",
    "cp:otoroshi.next.plugins.Cors",
    "cp:otoroshi.next.plugins.DisableHttp10",
    "cp:otoroshi.next.plugins.ApikeyCalls",
    "cp:otoroshi.next.plugins.OverrideHost",
    "cp:otoroshi.next.plugins.XForwardedHeaders",
    "cp:otoroshi.next.plugins.OtoroshiInfos",
    "cp:otoroshi.next.plugins.SendOtoroshiHeadersBack",
    "cp:otoroshi.next.plugins.OtoroshiChallenge",
  ];

  const PLUGINS = {
    api: API_PLUGINS,
    webapp: [
      "cp:otoroshi.next.plugins.ForceHttpsTraffic",
      "cp:otoroshi.next.plugins.BuildMode",
      "cp:otoroshi.next.plugins.MaintenanceMode",
      "cp:otoroshi.next.plugins.DisableHttp10",
      "cp:otoroshi.next.plugins.AuthModule",
      "cp:otoroshi.next.plugins.OverrideHost",
      "cp:otoroshi.next.plugins.OtoroshiInfos",
      "cp:otoroshi.next.plugins.OtoroshiChallenge",
      "cp:otoroshi.next.plugins.GzipResponseCompressor",
    ],
    empty: [],
    "graphql-proxy": ["cp:otoroshi.next.plugins.GraphQLProxy"],
    graphql: [...API_PLUGINS, "cp:otoroshi.next.plugins.GraphQLBackend"],
    mock: [...API_PLUGINS, "cp:otoroshi.next.plugins.MockResponses"],
  };

  const createRoute = async () => {
    setStatus("creating");

    try {
      const [
        plugins,
        oldPlugins,
        metadataPlugins,
        routeTemplate,
      ] = await Promise.all([
        Promise.resolve(Plugins()),
        getOldPlugins(),
        getPlugins(),
        nextClient.template(nextClient.ENTITIES.ROUTES),
      ]);

      const url = ["mock", "graphql"].includes(state.route.kind)
        ? { pathname: "/", hostname: "", protocol: "https:" }
        : new URL(state.route.url);

      const secured = url.protocol.includes("https");
      const selectedPlugins = PLUGINS[state.route.kind];

      const route = await nextClient.create(nextClient.ENTITIES.ROUTES, {
        ...routeTemplate,
        enabled: false,
        name: state.route.name,
        frontend: {
          ...routeTemplate.frontend,
          domains: [state.route.domain],
        },
        plugins: [
          ...plugins.map((p) => ({
            ...(metadataPlugins.find((metaPlugin) => metaPlugin.id === p.id) ||
              {}),
            ...p,
          })),
          ...oldPlugins,
        ]
          .filter((f) => selectedPlugins.includes(f.id))
          .map((plugin) => ({
            config: plugin.default_config,
            debug: false,
            enabled: true,
            exclude: [],
            include: [],
            bound_listeners: [],
            plugin: plugin.id,
          })),
        backend: {
          ...routeTemplate.backend,
          root: url.pathname || "/",
          targets: [
            {
              ...routeTemplate.backend.targets[0],
              hostname: url.hostname || "localhost",
              port: url.port ? ~~url.port : secured ? 443 : 80,
              tls: secured,
              tls_config: {
                ...routeTemplate.backend.targets[0].tls_config,
                enabled: secured,
              },
            },
          ],
        },
      });

      setCreatedRoute(route);
      setStatus("success");
    } catch (err) {
      setErrorMessage(err.message || "Failed to create route");
      setStatus("error");
    }
  };

  useEffect(() => {
    createRoute();
  }, []);

  if (status === "creating") {
    return (
      <div className="wizard-step wizard-step--centered">
        <div className="wizard-creating">
          <div className="wizard-creating__spinner">
            <div className="spinner-ring" />
          </div>
          <h2 className="wizard-creating__title">Creating your route...</h2>
          <p className="wizard-creating__subtitle">
            Setting up {template?.title || "route"} with{" "}
            {PLUGINS[state.route.kind]?.length || 0} plugins
          </p>
        </div>
      </div>
    );
  }

  if (status === "error") {
    return (
      <div className="wizard-step wizard-step--centered">
        <div className="wizard-error">
          <div className="wizard-error__icon">
            <svg
              width="48"
              height="48"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
            >
              <circle cx="12" cy="12" r="10" />
              <line x1="15" y1="9" x2="9" y2="15" />
              <line x1="9" y1="9" x2="15" y2="15" />
            </svg>
          </div>
          <h2 className="wizard-error__title">Something went wrong</h2>
          <p className="wizard-error__message">{errorMessage}</p>
          <button className="wizard-btn wizard-btn--secondary" onClick={onBack}>
            Go back
          </button>
        </div>
      </div>
    );
  }

  if (status === "success") {
    return (
      <div className="wizard-step wizard-step--centered">
        <div className="wizard-success">
          <div className="wizard-success__icon">{Icons.check}</div>
          <h2 className="wizard-success__title">Route created successfully!</h2>
          <p className="wizard-success__subtitle">
            Your route <strong>{state.route.name}</strong> is ready to be
            configured
          </p>

          <div className="wizard-success__summary">
            <div className="wizard-summary-item">
              <span className="wizard-summary-item__label">Domain</span>
              <span className="wizard-summary-item__value">
                {state.route.domain}
              </span>
            </div>
            <div className="wizard-summary-item">
              <span className="wizard-summary-item__label">Template</span>
              <span className="wizard-summary-item__value">
                {template?.title}
              </span>
            </div>
            {state.route.url && (
              <div className="wizard-summary-item">
                <span className="wizard-summary-item__label">Backend</span>
                <span className="wizard-summary-item__value">
                  {state.route.url}
                </span>
              </div>
            )}
          </div>

          <div className="wizard-success__actions">
            <a
              href={`/bo/dashboard/routes/${createdRoute?.id}?tab=flow`}
              className="wizard-btn wizard-btn--primary"
            >
              Configure plugins
              {Icons.chevronRight}
            </a>
            <a
              href={`/bo/dashboard/routes/${createdRoute?.id}?tab=informations`}
              className="wizard-btn wizard-btn--secondary"
            >
              View route details
            </a>
          </div>
        </div>
      </div>
    );
  }

  return null;
};

// Progress indicator
const ProgressSteps = ({ currentStep, totalSteps, steps }) => (
  <div className="wizard-progress">
    {steps.map((step, index) => {
      const stepNumber = index + 1;
      const isActive = stepNumber === currentStep;
      const isCompleted = stepNumber < currentStep;

      return (
        <div
          key={stepNumber}
          className={`wizard-progress__step ${
            isActive ? "wizard-progress__step--active" : ""
          } ${isCompleted ? "wizard-progress__step--completed" : ""}`}
        >
          <div className="wizard-progress__indicator">
            {isCompleted ? Icons.check : stepNumber}
          </div>
          <span className="wizard-progress__label">{step}</span>
        </div>
      );
    })}
  </div>
);

// Main Wizard Component
export class RouteWizard extends React.Component {
  state = {
    step: 1,
    route: {
      name: "My new route",
      domain: "",
      url: "",
      kind: "api",
    },
    error: undefined,
  };

  getSteps = () => {
    const baseSteps = ["Name", "Template", "Frontend"];
    if (!["mock", "graphql"].includes(this.state.route.kind)) {
      baseSteps.push("Backend");
    }
    baseSteps.push("Create");
    return baseSteps;
  };

  getTotalSteps = () => this.getSteps().length;

  prevStep = () => {
    let newStep = this.state.step - 1;
    // Skip backend step for mock/graphql when going back
    if (newStep === 4 && ["mock", "graphql"].includes(this.state.route.kind)) {
      newStep = 3;
    }
    this.setState({ step: newStep, error: undefined });
  };

  nextStep = () => {
    let newStep = this.state.step + 1;
    // Skip backend step for mock/graphql
    if (newStep === 4 && ["mock", "graphql"].includes(this.state.route.kind)) {
      newStep = 5;
    }
    this.setState({ step: newStep });
  };

  onRouteFieldChange = (field, value) => {
    this.setState({
      route: {
        ...this.state.route,
        [field]: value,
      },
    });
  };

  canProceed = () => {
    const { step, route, error } = this.state;
    switch (step) {
      case 1:
        return route.name && route.name.trim().length > 0;
      case 2:
        return !!route.kind;
      case 3:
        return route.domain && route.domain.trim().length > 0;
      case 4:
        if (["mock", "graphql"].includes(route.kind)) return true;
        return route.url && route.url.trim().length > 0 && !error;
      default:
        return true;
    }
  };

  render() {
    const { step, error } = this.state;
    const steps = this.getSteps();
    const totalSteps = this.getTotalSteps();
    const isLastStep = step === totalSteps;

    return (
      <div className="wizard-overlay">
        <div className="wizard-modal">
          {/* Header */}
          <div className="wizard-header">
            <div className="wizard-header__title">
              <span className="wizard-header__icon">{Icons.sparkles}</span>
              <span>Create a new route</span>
            </div>
            <button
              className="wizard-header__close"
              onClick={() => this.props.hide()}
              aria-label="Close"
            >
              {Icons.close}
            </button>
          </div>

          {/* Progress */}
          {step < totalSteps && (
            <ProgressSteps
              currentStep={step}
              totalSteps={totalSteps}
              steps={steps}
            />
          )}

          {/* Content */}
          <div className="wizard-body">
            {step === 1 && (
              <StepName
                state={this.state}
                onChange={(v) => this.onRouteFieldChange("name", v)}
              />
            )}
            {step === 2 && (
              <StepTemplate
                state={this.state}
                onChange={(v) => this.onRouteFieldChange("kind", v)}
              />
            )}
            {step === 3 && (
              <StepFrontend
                state={this.state}
                onChange={(v) => this.onRouteFieldChange("domain", v)}
                env={this.props.env}
              />
            )}
            {step === 4 &&
              !["mock", "graphql"].includes(this.state.route.kind) && (
                <StepBackend
                  state={this.state}
                  onChange={(v) => this.onRouteFieldChange("url", v)}
                  onError={(err) => this.setState({ error: err })}
                  error={error}
                  env={this.props.env}
                />
              )}
            {step === totalSteps && (
              <StepSummary
                state={this.state}
                env={this.props.env}
                onBack={this.prevStep}
              />
            )}
          </div>

          {/* Footer */}
          {step < totalSteps && (
            <div className="wizard-footer">
              {step > 1 ? (
                <button
                  className="wizard-btn wizard-btn--ghost"
                  onClick={this.prevStep}
                >
                  {Icons.chevronLeft}
                  Back
                </button>
              ) : (
                <div />
              )}
              <button
                className="wizard-btn wizard-btn--primary"
                onClick={this.nextStep}
                disabled={!this.canProceed()}
              >
                {step === totalSteps - 1 ? "Create route" : "Continue"}
                {Icons.chevronRight}
              </button>
            </div>
          )}
        </div>
      </div>
    );
  }
}
