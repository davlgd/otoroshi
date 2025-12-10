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

// Theme Material UI
const theme = createTheme({
  palette: {
    mode: "light",
    primary: {
      main: "#f9b000",
      contrastText: "#000",
    },
    background: {
      default: "#f5f5f5",
      paper: "#ffffff",
    },
  },
  typography: {
    fontFamily: '"Inter", "Segoe UI", "Roboto", "Helvetica", sans-serif',
  },
  shape: {
    borderRadius: 8,
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
