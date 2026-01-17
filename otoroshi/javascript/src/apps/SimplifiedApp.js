import React, { useState, createContext, useContext } from "react";
import {
  BrowserRouter as Router,
  Route,
  Switch,
  Redirect,
} from "react-router-dom";
import { ThemeProvider, createTheme } from "@mui/material/styles";
import CssBaseline from "@mui/material/CssBaseline";
import { RouteCreationPage } from "../pages/SimpleUI/RouteCreationPage";
import { Toasts } from "../components/Toasts";

// Context pour partager l'environnement
export const SimpleUIContext = createContext({
  env: {},
  user: null,
});

export const useSimpleUI = () => useContext(SimpleUIContext);

// Theme Material UI - Dark Mode
const theme = createTheme({
  palette: {
    mode: "dark",
    primary: {
      main: "#f9b000",
      contrastText: "#18181b",
    },
    secondary: {
      main: "#a1a1aa",
    },
    background: {
      default: "#18181b",
      paper: "#27272a",
    },
    text: {
      primary: "#ffffff",
      secondary: "#a1a1aa",
    },
    divider: "#3f3f46",
    error: {
      main: "#ef4444",
    },
    success: {
      main: "#10b981",
    },
  },
  typography: {
    fontFamily:
      '"Inter", -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif',
  },
  shape: {
    borderRadius: 8,
  },
  components: {
    MuiCssBaseline: {
      styleOverrides: {
        body: {
          backgroundColor: "#18181b",
        },
      },
    },
    MuiCard: {
      styleOverrides: {
        root: {
          backgroundImage: "none",
          border: "1px solid #3f3f46",
        },
      },
    },
    MuiTextField: {
      styleOverrides: {
        root: {
          "& .MuiOutlinedInput-root": {
            backgroundColor: "#18181b",
            "& fieldset": {
              borderColor: "#3f3f46",
            },
            "&:hover fieldset": {
              borderColor: "#52525b",
            },
            "&.Mui-focused fieldset": {
              borderColor: "#f9b000",
            },
          },
        },
      },
    },
    MuiButton: {
      styleOverrides: {
        contained: {
          boxShadow: "none",
          "&:hover": {
            boxShadow: "0 4px 12px rgba(249, 176, 0, 0.3)",
          },
        },
        outlined: {
          borderColor: "#3f3f46",
          "&:hover": {
            borderColor: "#52525b",
            backgroundColor: "rgba(255, 255, 255, 0.05)",
          },
        },
      },
    },
    MuiAppBar: {
      styleOverrides: {
        root: {
          backgroundImage: "none",
        },
      },
    },
  },
});

export const SimplifiedApp = ({ env }) => {
  const [user, setUser] = useState(window.__user || null);

  return (
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <SimpleUIContext.Provider value={{ env, user }}>
        <Router basename="/bo/simple-ui">
          <Switch>
            <Route
              exact
              path="/"
              render={() => <RouteCreationPage env={env} />}
            />
            <Route
              path="/create-route"
              render={() => <RouteCreationPage env={env} />}
            />
            <Redirect to="/" />
          </Switch>
          <Toasts />
        </Router>
      </SimpleUIContext.Provider>
    </ThemeProvider>
  );
};
