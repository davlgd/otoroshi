import React from "react";
import { BrowserRouter as Router, Route, Switch } from "react-router-dom";
import { ThemeProvider, createTheme } from "@mui/material/styles";
import CssBaseline from "@mui/material/CssBaseline";
import { RouteCreationPage } from "../pages/SimpleUI/RouteCreationPage";
import "../style/simpleui.scss";

// Theme Material UI moderne
const theme = createTheme({
  palette: {
    mode: "light",
    primary: {
      main: "#f9b000", // Otoroshi yellow
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

export class SimpleUIApp extends React.Component {
  // L'authentification est geree cote serveur via BackOfficeActionAuth
  // Si on arrive ici, l'utilisateur est forcement authentifie

  render() {
    const { env, user } = this.props;

    return (
      <ThemeProvider theme={theme}>
        <CssBaseline />
        <Router basename="/bo/simple-ui">
          <Switch>
            <Route
              path="/"
              exact
              render={() => <RouteCreationPage env={env} user={user} />}
            />
          </Switch>
        </Router>
      </ThemeProvider>
    );
  }
}
