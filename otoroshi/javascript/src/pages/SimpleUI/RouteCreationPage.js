import React, { useState } from "react";
import {
  Box,
  Card,
  CardContent,
  TextField,
  Button,
  Typography,
  AppBar,
  Toolbar,
  Alert,
  CircularProgress,
  Container,
  Link,
  InputAdornment,
  Divider,
} from "@mui/material";
import AddIcon from "@mui/icons-material/Add";
import LogoutIcon from "@mui/icons-material/Logout";
import DashboardIcon from "@mui/icons-material/Dashboard";
import RouteIcon from "@mui/icons-material/Route";
import LanguageIcon from "@mui/icons-material/Language";
import StorageIcon from "@mui/icons-material/Storage";

export function RouteCreationPage({ env }) {
  // Debug: vérifier les valeurs env
  console.log("RouteCreationPage env:", env);

  // Valeurs par défaut depuis l'environnement
  const defaultDomain = env?.routeBaseDomain || "oto.tools";
  const defaultBackend = env?.defaultBackendTls
    ? `https://${env?.defaultBackendHost || "request.otoroshi.io"}:${
        env?.defaultBackendPort || 443
      }`
    : `http://${env?.defaultBackendHost || "request.otoroshi.io"}:${
        env?.defaultBackendPort || 80
      }`;

  console.log("Default values:", { defaultDomain, defaultBackend });

  const [formData, setFormData] = useState({
    name: "My API",
    domain: `my-api.${defaultDomain}`,
    backendUrl: defaultBackend,
  });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  console.log("Form data:", formData);

  const generateRouteId = () => {
    const chars =
      "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    let result = "route_";
    for (let i = 0; i < 32; i++) {
      result += chars.charAt(Math.floor(Math.random() * chars.length));
    }
    return result;
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    setLoading(true);
    setError(null);

    // Validation basique
    if (!formData.name || !formData.domain || !formData.backendUrl) {
      setError("All fields are required");
      setLoading(false);
      return;
    }

    // Valider l'URL backend
    let backendUrl;
    try {
      backendUrl = new URL(formData.backendUrl);
    } catch (err) {
      setError(
        "Invalid backend URL. Please enter a valid URL (e.g., https://backend.example.com)"
      );
      setLoading(false);
      return;
    }

    // Construire l'objet NgRoute
    const route = {
      id: generateRouteId(),
      name: formData.name,
      description: `Route created via Simple UI`,
      enabled: true,
      debugFlow: false,
      capture: false,
      exportReporting: false,
      groups: ["default"],
      location: {
        tenant: "default",
        teams: ["default"],
      },
      frontend: {
        domains: [formData.domain],
        headers: {},
        query: {},
        cookies: {},
        methods: [],
        stripPath: true,
        exact: false,
      },
      backend: {
        targets: [
          {
            id: "target_1",
            hostname: backendUrl.hostname,
            port:
              backendUrl.port || (backendUrl.protocol === "https:" ? 443 : 80),
            tls: backendUrl.protocol === "https:",
            weight: 1,
            protocol: "HTTP/1.1",
            predicate: { type: "AlwaysMatch" },
            ipAddress: null,
            tlsConfig: {
              enabled: false,
              loose: false,
              trustAll: false,
            },
          },
        ],
        root: "/",
        rewrite: false,
        loadBalancing: { type: "RoundRobin" },
        client: {
          useCircuitBreaker: true,
          retries: 1,
          maxErrors: 20,
          retryInitialDelay: 50,
          backoffFactor: 2,
          callTimeout: 30000,
          callAndStreamTimeout: 120000,
          connectionTimeout: 10000,
          idleTimeout: 60000,
          globalTimeout: 30000,
        },
      },
      plugins: [
        {
          enabled: true,
          plugin: "cp:otoroshi.next.plugins.OverrideHost",
          config: {},
        },
      ],
    };

    try {
      const response = await fetch("/bo/api/proxy/api/routes", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        credentials: "include",
        body: JSON.stringify(route),
      });

      if (response.ok) {
        const createdRoute = await response.json();
        // Redirection vers le Designer
        window.location.href = `/bo/dashboard/routes/${createdRoute.id}?tab=flow`;
      } else {
        const contentType = response.headers.get("content-type");
        if (contentType && contentType.includes("application/json")) {
          const data = await response.json();
          setError(data.error || "Failed to create route");
        } else {
          setError("Failed to create route. Please check your permissions.");
        }
      }
    } catch (err) {
      setError("Connection error. Please try again.");
    } finally {
      setLoading(false);
    }
  };

  const handleLogout = async () => {
    try {
      await fetch("/backoffice/auth0/logout", { credentials: "include" });
      window.location.href = "/bo/simple-ui/login";
    } catch (err) {
      window.location.href = "/bo/simple-ui/login";
    }
  };

  return (
    <>
      <AppBar
        position="static"
        elevation={0}
        sx={{
          bgcolor: "background.paper",
          borderBottom: "1px solid",
          borderColor: "divider",
        }}
      >
        <Toolbar>
          <Typography
            variant="h6"
            sx={{ flexGrow: 1, fontWeight: 700, color: "primary.main" }}
          >
            <RouteIcon sx={{ mr: 1, verticalAlign: "middle" }} />
            Otoroshi Simple UI
          </Typography>
          <Button
            href="/bo/dashboard"
            startIcon={<DashboardIcon />}
            sx={{ mr: 2, textTransform: "none", color: "text.secondary" }}
          >
            Advanced Interface
          </Button>
          <Button
            variant="outlined"
            startIcon={<LogoutIcon />}
            onClick={handleLogout}
            sx={{ textTransform: "none" }}
          >
            Logout
          </Button>
        </Toolbar>
      </AppBar>

      <Box
        sx={{
          minHeight: "calc(100vh - 64px)",
          bgcolor: "background.default",
          py: 6,
        }}
      >
        <Container maxWidth="md">
          <Card elevation={0} sx={{ borderRadius: 3 }} className="fade-in">
            <CardContent sx={{ p: 5 }}>
              <Box sx={{ textAlign: "center", mb: 4 }}>
                <Typography
                  variant="h4"
                  gutterBottom
                  sx={{ fontWeight: 700, color: "text.primary" }}
                >
                  Create a New Route
                </Typography>
                <Typography variant="body1" color="text.secondary" paragraph>
                  Define a simple API route with just three essential fields.
                  Your route will be created and you'll be redirected to the
                  advanced designer for further configuration.
                </Typography>
              </Box>

              <Divider sx={{ my: 3, borderColor: "divider" }} />

              {error && (
                <Alert
                  severity="error"
                  sx={{ mb: 3 }}
                  onClose={() => setError(null)}
                >
                  {error}
                </Alert>
              )}

              <Box component="form" onSubmit={handleSubmit}>
                <TextField
                  fullWidth
                  label="Route Name"
                  variant="outlined"
                  margin="normal"
                  value={formData.name}
                  onChange={(e) =>
                    setFormData({ ...formData, name: e.target.value })
                  }
                  disabled={loading}
                  required
                  placeholder="My API Route"
                  helperText="A friendly name for your route"
                  InputProps={{
                    startAdornment: (
                      <InputAdornment position="start">
                        <RouteIcon color="action" />
                      </InputAdornment>
                    ),
                  }}
                  sx={{ mb: 3 }}
                />

                <TextField
                  fullWidth
                  label="Domain"
                  variant="outlined"
                  margin="normal"
                  value={formData.domain}
                  onChange={(e) =>
                    setFormData({ ...formData, domain: e.target.value })
                  }
                  disabled={loading}
                  required
                  placeholder="api.example.com"
                  helperText="The domain where your API will be accessible"
                  InputProps={{
                    startAdornment: (
                      <InputAdornment position="start">
                        <LanguageIcon color="action" />
                      </InputAdornment>
                    ),
                  }}
                  sx={{ mb: 3 }}
                />

                <TextField
                  fullWidth
                  label="Backend URL"
                  variant="outlined"
                  margin="normal"
                  value={formData.backendUrl}
                  onChange={(e) =>
                    setFormData({ ...formData, backendUrl: e.target.value })
                  }
                  disabled={loading}
                  required
                  placeholder="https://backend.example.com"
                  helperText="The backend server URL that will handle the requests"
                  InputProps={{
                    startAdornment: (
                      <InputAdornment position="start">
                        <StorageIcon color="action" />
                      </InputAdornment>
                    ),
                  }}
                  sx={{ mb: 4 }}
                />

                <Button
                  fullWidth
                  type="submit"
                  variant="contained"
                  size="large"
                  disabled={
                    loading ||
                    !formData.name ||
                    !formData.domain ||
                    !formData.backendUrl
                  }
                  startIcon={
                    loading ? (
                      <CircularProgress size={20} color="inherit" />
                    ) : (
                      <AddIcon />
                    )
                  }
                  sx={{
                    py: 1.5,
                    textTransform: "none",
                    fontSize: "1.1rem",
                    fontWeight: 600,
                  }}
                >
                  {loading ? "Creating Route..." : "Create Route"}
                </Button>
              </Box>
            </CardContent>
          </Card>

          <Box sx={{ mt: 3, textAlign: "center" }}>
            <Typography variant="caption" color="text.secondary">
              After creation, you'll be redirected to the advanced route
              designer for additional configuration.
            </Typography>
          </Box>
        </Container>
      </Box>

      {/* Footer */}
      <Box
        sx={{
          py: 2,
          textAlign: "center",
          color: "text.secondary",
          fontSize: "12px",
          borderTop: "1px solid",
          borderColor: "divider",
        }}
      >
        <Typography variant="caption">
          Otoroshi Simple UI - Powered by Otoroshi
        </Typography>
      </Box>
    </>
  );
}
