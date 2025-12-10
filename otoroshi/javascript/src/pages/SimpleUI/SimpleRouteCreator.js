import React, { useState, useCallback, useEffect } from "react";
import { useHistory } from "react-router-dom";
import { nextClient } from "../../services/BackOfficeServices";

// Icons as simple SVG components
const Icons = {
  ArrowRight: () => (
    <svg
      width="20"
      height="20"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
    >
      <line x1="5" y1="12" x2="19" y2="12" />
      <polyline points="12 5 19 12 12 19" />
    </svg>
  ),
  ArrowLeft: () => (
    <svg
      width="20"
      height="20"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
    >
      <line x1="19" y1="12" x2="5" y2="12" />
      <polyline points="12 19 5 12 12 5" />
    </svg>
  ),
  Check: () => (
    <svg
      width="20"
      height="20"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
    >
      <polyline points="20 6 9 17 4 12" />
    </svg>
  ),
  Globe: () => (
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
  Server: () => (
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
  Zap: () => (
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
  Loader: () => (
    <svg
      width="24"
      height="24"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      className="sui-spinner"
    >
      <line x1="12" y1="2" x2="12" y2="6" />
      <line x1="12" y1="18" x2="12" y2="22" />
      <line x1="4.93" y1="4.93" x2="7.76" y2="7.76" />
      <line x1="16.24" y1="16.24" x2="19.07" y2="19.07" />
      <line x1="2" y1="12" x2="6" y2="12" />
      <line x1="18" y1="12" x2="22" y2="12" />
      <line x1="4.93" y1="19.07" x2="7.76" y2="16.24" />
      <line x1="16.24" y1="7.76" x2="19.07" y2="4.93" />
    </svg>
  ),
};

// Step indicator component
const StepIndicator = ({ currentStep, totalSteps }) => {
  return (
    <div className="sui-steps">
      {Array.from({ length: totalSteps }, (_, i) => (
        <div
          key={i}
          className={`sui-step ${
            i + 1 === currentStep ? "sui-step--active" : ""
          } ${i + 1 < currentStep ? "sui-step--completed" : ""}`}
        >
          <div className="sui-step-number">
            {i + 1 < currentStep ? <Icons.Check /> : i + 1}
          </div>
          <span className="sui-step-label">
            {i === 0 && "Name"}
            {i === 1 && "Frontend"}
            {i === 2 && "Backend"}
          </span>
        </div>
      ))}
    </div>
  );
};

// Step 1: Route Name
const StepName = ({ value, onChange, onNext }) => {
  const handleKeyPress = (e) => {
    if (e.key === "Enter" && value.trim()) {
      onNext();
    }
  };

  return (
    <div className="sui-step-content">
      <div className="sui-step-header">
        <h2>Name your route</h2>
        <p>Give a meaningful name to identify your API route</p>
      </div>
      <div className="sui-form-group">
        <label htmlFor="route-name">Route name</label>
        <input
          id="route-name"
          type="text"
          className="sui-input"
          placeholder="e.g., My API, Customer Service, Payment Gateway..."
          value={value}
          onChange={(e) => onChange(e.target.value)}
          onKeyPress={handleKeyPress}
          autoFocus
        />
      </div>
    </div>
  );
};

// Step 2: Frontend (Domain)
const StepFrontend = ({ value, onChange, onNext }) => {
  const handleKeyPress = (e) => {
    if (e.key === "Enter" && value.trim()) {
      onNext();
    }
  };

  return (
    <div className="sui-step-content">
      <div className="sui-step-header">
        <div className="sui-step-icon">
          <Icons.Globe />
        </div>
        <h2>Define the frontend</h2>
        <p>This is the public URL where your API will be accessible</p>
      </div>
      <div className="sui-form-group">
        <label htmlFor="route-domain">Domain</label>
        <input
          id="route-domain"
          type="text"
          className="sui-input"
          placeholder="e.g., api.mycompany.com or api.mycompany.com/v1"
          value={value}
          onChange={(e) => onChange(e.target.value)}
          onKeyPress={handleKeyPress}
          autoFocus
        />
        <span className="sui-input-hint">
          Enter the domain name through which users will access your API
        </span>
      </div>
    </div>
  );
};

// Step 3: Backend (Target URL)
const StepBackend = ({ value, onChange, error, onError }) => {
  const validateUrl = useCallback(
    (url) => {
      if (!url) {
        onError(null);
        return;
      }
      try {
        if (!url.includes("://")) {
          onError("Please include the protocol (http:// or https://)");
          return;
        }
        new URL(url);
        onError(null);
      } catch (err) {
        onError("Please enter a valid URL");
      }
    },
    [onError]
  );

  const handleChange = (e) => {
    const newValue = e.target.value;
    onChange(newValue);
    validateUrl(newValue);
  };

  return (
    <div className="sui-step-content">
      <div className="sui-step-header">
        <div className="sui-step-icon">
          <Icons.Server />
        </div>
        <h2>Define the backend</h2>
        <p>The URL of your actual backend service</p>
      </div>
      <div className="sui-form-group">
        <label htmlFor="route-backend">Target URL</label>
        <input
          id="route-backend"
          type="text"
          className={`sui-input ${error ? "sui-input--error" : ""}`}
          placeholder="e.g., https://internal-api.mycompany.local:8080"
          value={value}
          onChange={handleChange}
          autoFocus
        />
        {error && <span className="sui-input-error">{error}</span>}
        {!error && (
          <span className="sui-input-hint">
            Where should Otoroshi forward the requests to?
          </span>
        )}
      </div>
    </div>
  );
};

// Creating state component
const CreatingRoute = ({ routeName, onComplete, onError }) => {
  const [status, setStatus] = useState("creating");
  const [progress, setProgress] = useState(0);

  useEffect(() => {
    const interval = setInterval(() => {
      setProgress((p) => Math.min(p + 10, 90));
    }, 200);

    return () => clearInterval(interval);
  }, []);

  return (
    <div className="sui-step-content sui-creating">
      <div className="sui-creating-animation">
        <Icons.Loader />
      </div>
      <h2>Creating your route...</h2>
      <p>{routeName}</p>
      <div className="sui-progress">
        <div className="sui-progress-bar" style={{ width: `${progress}%` }} />
      </div>
    </div>
  );
};

// Success state component
const RouteCreated = ({ routeId, routeName }) => {
  return (
    <div className="sui-step-content sui-success">
      <div className="sui-success-icon">
        <Icons.Check />
      </div>
      <h2>Route created successfully!</h2>
      <p className="sui-success-name">{routeName}</p>
      <div className="sui-success-actions">
        <a
          href={`/bo/dashboard/routes/${routeId}?tab=flow`}
          className="sui-btn sui-btn--primary sui-btn--large"
        >
          <Icons.Zap />
          <span>Open in Route Designer</span>
        </a>
        <a href="/bo/simple-ui" className="sui-btn sui-btn--secondary">
          Create another route
        </a>
      </div>
    </div>
  );
};

// Main component
export const SimpleRouteCreator = () => {
  const [step, setStep] = useState(1);
  const [routeName, setRouteName] = useState("");
  const [domain, setDomain] = useState("");
  const [backendUrl, setBackendUrl] = useState("");
  const [backendError, setBackendError] = useState(null);
  const [isCreating, setIsCreating] = useState(false);
  const [createdRouteId, setCreatedRouteId] = useState(null);
  const [error, setError] = useState(null);

  const totalSteps = 3;

  const canProceed = () => {
    switch (step) {
      case 1:
        return routeName.trim().length > 0;
      case 2:
        return domain.trim().length > 0;
      case 3:
        return backendUrl.trim().length > 0 && !backendError;
      default:
        return false;
    }
  };

  const handleNext = () => {
    if (step < totalSteps) {
      setStep(step + 1);
    } else {
      createRoute();
    }
  };

  const handleBack = () => {
    if (step > 1) {
      setStep(step - 1);
    }
  };

  const createRoute = async () => {
    setIsCreating(true);
    setError(null);

    try {
      // Get template only
      const template = await nextClient.template(nextClient.ENTITIES.ROUTES);

      // Parse backend URL
      const url = new URL(backendUrl);
      const secured = url.protocol.includes("https");

      // Basic plugins for a simple API route (hardcoded to avoid heavy imports)
      const routePlugins = [
        {
          enabled: true,
          debug: false,
          plugin: "cp:otoroshi.next.plugins.OverrideHost",
          include: [],
          exclude: [],
          config: {},
          bound_listeners: [],
        },
        {
          enabled: true,
          debug: false,
          plugin: "cp:otoroshi.next.plugins.SendOtoroshiHeadersBack",
          include: [],
          exclude: [],
          config: {},
          bound_listeners: [],
        },
      ];

      // Create the route
      const newRoute = await nextClient.create(nextClient.ENTITIES.ROUTES, {
        ...template,
        enabled: true,
        name: routeName,
        frontend: {
          ...template.frontend,
          domains: [domain],
        },
        plugins: routePlugins,
        backend: {
          ...template.backend,
          root: url.pathname || "/",
          targets: [
            {
              ...template.backend.targets[0],
              hostname: url.hostname,
              port: url.port ? parseInt(url.port) : secured ? 443 : 80,
              tls: secured,
              tls_config: {
                ...template.backend.targets[0].tls_config,
                enabled: secured,
              },
            },
          ],
        },
      });

      setCreatedRouteId(newRoute.id);
      setIsCreating(false);
    } catch (err) {
      console.error("Error creating route:", err);
      setError(err.message || "An error occurred while creating the route");
      setIsCreating(false);
    }
  };

  // If route is created, show success
  if (createdRouteId) {
    return (
      <div className="sui-creator">
        <RouteCreated routeId={createdRouteId} routeName={routeName} />
      </div>
    );
  }

  // If creating, show loader
  if (isCreating) {
    return (
      <div className="sui-creator">
        <CreatingRoute routeName={routeName} />
      </div>
    );
  }

  return (
    <div className="sui-creator">
      <div className="sui-creator-card">
        <StepIndicator currentStep={step} totalSteps={totalSteps} />

        {error && (
          <div className="sui-error-banner">
            <span>{error}</span>
            <button onClick={() => setError(null)}>Dismiss</button>
          </div>
        )}

        {step === 1 && (
          <StepName
            value={routeName}
            onChange={setRouteName}
            onNext={handleNext}
          />
        )}
        {step === 2 && (
          <StepFrontend
            value={domain}
            onChange={setDomain}
            onNext={handleNext}
          />
        )}
        {step === 3 && (
          <StepBackend
            value={backendUrl}
            onChange={setBackendUrl}
            error={backendError}
            onError={setBackendError}
          />
        )}

        <div className="sui-creator-actions">
          {step > 1 && (
            <button className="sui-btn sui-btn--secondary" onClick={handleBack}>
              <Icons.ArrowLeft />
              <span>Back</span>
            </button>
          )}
          <button
            className="sui-btn sui-btn--primary"
            onClick={handleNext}
            disabled={!canProceed()}
          >
            <span>{step === totalSteps ? "Create Route" : "Continue"}</span>
            {step < totalSteps && <Icons.ArrowRight />}
          </button>
        </div>
      </div>
    </div>
  );
};
