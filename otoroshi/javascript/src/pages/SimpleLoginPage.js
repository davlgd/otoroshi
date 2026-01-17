import React, { Component } from "react";

// Inline CSS for animations (will be injected once)
const injectStyles = () => {
  if (document.getElementById("otoroshi-login-styles")) return;
  const style = document.createElement("style");
  style.id = "otoroshi-login-styles";
  style.textContent = `
    @keyframes otoroshi-fade-in {
      from { opacity: 0; transform: translateY(8px); }
      to { opacity: 1; transform: translateY(0); }
    }
    @keyframes otoroshi-glow-pulse {
      0%, 100% { opacity: 0.4; }
      50% { opacity: 0.8; }
    }
    @keyframes otoroshi-shimmer {
      0% { background-position: -200% 0; }
      100% { background-position: 200% 0; }
    }
    @keyframes otoroshi-spin {
      from { transform: rotate(0deg); }
      to { transform: rotate(360deg); }
    }
    .otoroshi-login-input::placeholder {
      color: #52525b;
    }
    .otoroshi-login-input:focus {
      border-color: #f9b000;
      box-shadow: 0 0 0 1px #f9b000, 0 0 20px rgba(249, 176, 0, 0.15);
    }
    .otoroshi-login-btn:hover:not(:disabled) {
      background: linear-gradient(135deg, #fbbf24 0%, #f59e0b 100%);
      box-shadow: 0 8px 24px rgba(249, 176, 0, 0.35);
      transform: translateY(-1px);
    }
    .otoroshi-login-btn:active:not(:disabled) {
      transform: translateY(0);
    }
    .otoroshi-login-btn:disabled {
      opacity: 0.7;
      cursor: not-allowed;
    }
  `;
  document.head.appendChild(style);
};

export class SimpleLoginPage extends Component {
  state = {
    email: "",
    loading: false,
    error: null,
  };

  componentDidMount() {
    injectStyles();
  }

  getLink = (email) => {
    if (this.props.redirect.length <= 0) {
      return `/privateapps/generic/login?route=${this.props.route}&email=${email}&hash=${this.props.hash}`;
    } else {
      return `/privateapps/generic/login?redirect=${btoa(
        this.props.redirect
      )}&route=${this.props.route}&email=${email}&hash=${this.props.hash}`;
    }
  };

  redirect = (e) => {
    e.preventDefault();
    if (!this.state.email || this.state.loading) return;

    this.setState({ loading: true, error: null });
    const loginPage = this.getLink(this.state.email);

    fetch(loginPage, {
      credentials: "include",
      redirect: "manual",
    })
      .then((r) => {
        if (r.status === 500) {
          return {
            "Otoroshi-Error": "Authentication failed. Please try again.",
          };
        } else if (r.status > 300 && r.status < 400) {
          window.location.replace(r.response.Location);
          return null;
        } else if (r.headers.get("Content-Type") === "application/json") {
          return r.json();
        } else {
          window.location.replace(loginPage);
          return null;
        }
      })
      .then((result) => {
        if (result && result["Otoroshi-Error"]) {
          this.setState({ error: result, loading: false });
        }
      })
      .catch(() => {
        this.setState({
          error: {
            "Otoroshi-Error": "Connection error. Please check your network.",
          },
          loading: false,
        });
      });
  };

  render() {
    const { email, loading, error } = this.state;

    return (
      <div style={styles.container}>
        {/* Subtle gradient orb in background */}
        <div style={styles.backgroundOrb} />

        <div style={styles.content}>
          {/* Logo and brand */}
          <div style={styles.brandSection}>
            <div style={styles.logoContainer}>
              <img
                src={this.props.otoroshiLogo}
                style={styles.logo}
                alt="Otoroshi"
              />
            </div>
          </div>

          {/* Login card */}
          <div style={styles.card}>
            <div style={styles.cardHeader}>
              <h1 style={styles.title}>Sign in</h1>
              <p style={styles.subtitle}>
                Enter your email to access the console
              </p>
            </div>

            <form onSubmit={this.redirect} style={styles.form}>
              <div style={styles.inputGroup}>
                <label style={styles.label}>Email address</label>
                <input
                  type="email"
                  value={email}
                  onChange={(e) =>
                    this.setState({
                      email: e.target.value,
                      error: null,
                    })
                  }
                  className="otoroshi-login-input"
                  style={{
                    ...styles.input,
                    ...(error ? styles.inputError : {}),
                  }}
                  placeholder="you@company.com"
                  autoFocus
                  disabled={loading}
                  autoComplete="email"
                />
              </div>

              {error && (
                <div style={styles.errorContainer}>
                  <svg
                    style={styles.errorIcon}
                    viewBox="0 0 20 20"
                    fill="currentColor"
                  >
                    <path
                      fillRule="evenodd"
                      d="M10 18a8 8 0 100-16 8 8 0 000 16zM8.707 7.293a1 1 0 00-1.414 1.414L8.586 10l-1.293 1.293a1 1 0 101.414 1.414L10 11.414l1.293 1.293a1 1 0 001.414-1.414L11.414 10l1.293-1.293a1 1 0 00-1.414-1.414L10 8.586 8.707 7.293z"
                      clipRule="evenodd"
                    />
                  </svg>
                  <span>{error["Otoroshi-Error"]}</span>
                </div>
              )}

              <button
                type="submit"
                className="otoroshi-login-btn"
                style={styles.button}
                disabled={loading || !email}
              >
                {loading ? (
                  <span style={styles.buttonContent}>
                    <svg style={styles.spinner} viewBox="0 0 24 24" fill="none">
                      <circle
                        style={styles.spinnerTrack}
                        cx="12"
                        cy="12"
                        r="10"
                        stroke="currentColor"
                        strokeWidth="3"
                      />
                      <path
                        style={styles.spinnerHead}
                        d="M12 2a10 10 0 0110 10"
                        stroke="currentColor"
                        strokeWidth="3"
                        strokeLinecap="round"
                      />
                    </svg>
                    Authenticating...
                  </span>
                ) : (
                  <span style={styles.buttonContent}>
                    Continue
                    <svg
                      style={styles.arrowIcon}
                      viewBox="0 0 20 20"
                      fill="currentColor"
                    >
                      <path
                        fillRule="evenodd"
                        d="M10.293 3.293a1 1 0 011.414 0l6 6a1 1 0 010 1.414l-6 6a1 1 0 01-1.414-1.414L14.586 11H3a1 1 0 110-2h11.586l-4.293-4.293a1 1 0 010-1.414z"
                        clipRule="evenodd"
                      />
                    </svg>
                  </span>
                )}
              </button>
            </form>

            <div style={styles.divider}>
              <span style={styles.dividerLine} />
              <span style={styles.dividerText}>Secure authentication</span>
              <span style={styles.dividerLine} />
            </div>

            <div style={styles.securityBadge}>
              <svg
                style={styles.shieldIcon}
                viewBox="0 0 20 20"
                fill="currentColor"
              >
                <path
                  fillRule="evenodd"
                  d="M2.166 4.999A11.954 11.954 0 0010 1.944 11.954 11.954 0 0017.834 5c.11.65.166 1.32.166 2.001 0 5.225-3.34 9.67-8 11.317C5.34 16.67 2 12.225 2 7c0-.682.057-1.35.166-2.001zm11.541 3.708a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z"
                  clipRule="evenodd"
                />
              </svg>
              <span>Protected by Otoroshi API Gateway</span>
            </div>
          </div>

          {/* Footer */}
          <div style={styles.footer}>
            <span style={styles.footerText}>Cloud-native API Gateway</span>
            <span style={styles.footerDot}>·</span>
            <a
              href="https://www.otoroshi.io"
              target="_blank"
              rel="noopener noreferrer"
              style={styles.footerLink}
            >
              otoroshi.io
            </a>
          </div>
        </div>
      </div>
    );
  }
}

const styles = {
  container: {
    minHeight: "100vh",
    display: "flex",
    alignItems: "center",
    justifyContent: "center",
    backgroundColor: "#09090b",
    fontFamily:
      '"Inter", -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif',
    padding: "24px",
    position: "relative",
    overflow: "hidden",
  },
  backgroundOrb: {
    position: "absolute",
    top: "10%",
    left: "50%",
    transform: "translateX(-50%)",
    width: "600px",
    height: "600px",
    background:
      "radial-gradient(circle, rgba(249, 176, 0, 0.08) 0%, transparent 70%)",
    pointerEvents: "none",
    animation: "otoroshi-glow-pulse 4s ease-in-out infinite",
  },
  content: {
    position: "relative",
    zIndex: 1,
    width: "100%",
    maxWidth: "380px",
    animation: "otoroshi-fade-in 0.5s ease-out",
  },
  brandSection: {
    textAlign: "center",
    marginBottom: "32px",
  },
  logoContainer: {
    display: "inline-flex",
    alignItems: "center",
    justifyContent: "center",
  },
  logo: {
    height: "40px",
    width: "auto",
  },
  card: {
    background:
      "linear-gradient(145deg, rgba(39, 39, 42, 0.8) 0%, rgba(24, 24, 27, 0.9) 100%)",
    backdropFilter: "blur(20px)",
    border: "1px solid rgba(63, 63, 70, 0.5)",
    borderRadius: "16px",
    padding: "32px",
    boxShadow: "0 25px 50px -12px rgba(0, 0, 0, 0.5)",
  },
  cardHeader: {
    marginBottom: "28px",
  },
  title: {
    margin: "0 0 8px",
    color: "#fafafa",
    fontSize: "22px",
    fontWeight: "600",
    letterSpacing: "-0.02em",
  },
  subtitle: {
    margin: 0,
    color: "#71717a",
    fontSize: "14px",
    lineHeight: "1.5",
  },
  form: {
    display: "flex",
    flexDirection: "column",
    gap: "20px",
  },
  inputGroup: {
    display: "flex",
    flexDirection: "column",
    gap: "8px",
  },
  label: {
    color: "#a1a1aa",
    fontSize: "13px",
    fontWeight: "500",
  },
  input: {
    width: "100%",
    padding: "12px 14px",
    fontSize: "14px",
    color: "#fafafa",
    backgroundColor: "rgba(9, 9, 11, 0.8)",
    border: "1px solid #3f3f46",
    borderRadius: "10px",
    outline: "none",
    transition: "all 0.2s ease",
    boxSizing: "border-box",
  },
  inputError: {
    borderColor: "#dc2626",
    boxShadow: "0 0 0 1px #dc2626",
  },
  errorContainer: {
    display: "flex",
    alignItems: "center",
    gap: "8px",
    padding: "12px 14px",
    backgroundColor: "rgba(220, 38, 38, 0.1)",
    border: "1px solid rgba(220, 38, 38, 0.2)",
    borderRadius: "10px",
    color: "#fca5a5",
    fontSize: "13px",
  },
  errorIcon: {
    width: "16px",
    height: "16px",
    flexShrink: 0,
    color: "#f87171",
  },
  button: {
    width: "100%",
    padding: "12px 20px",
    fontSize: "14px",
    fontWeight: "600",
    color: "#09090b",
    background: "linear-gradient(135deg, #f9b000 0%, #f59e0b 100%)",
    border: "none",
    borderRadius: "10px",
    cursor: "pointer",
    transition: "all 0.2s ease",
    boxShadow: "0 4px 14px rgba(249, 176, 0, 0.25)",
  },
  buttonContent: {
    display: "flex",
    alignItems: "center",
    justifyContent: "center",
    gap: "8px",
  },
  arrowIcon: {
    width: "16px",
    height: "16px",
    transition: "transform 0.2s ease",
  },
  spinner: {
    width: "18px",
    height: "18px",
    animation: "otoroshi-spin 1s linear infinite",
  },
  spinnerTrack: {
    opacity: 0.2,
  },
  spinnerHead: {
    opacity: 1,
  },
  divider: {
    display: "flex",
    alignItems: "center",
    gap: "12px",
    margin: "24px 0 20px",
  },
  dividerLine: {
    flex: 1,
    height: "1px",
    background: "linear-gradient(90deg, transparent, #3f3f46, transparent)",
  },
  dividerText: {
    color: "#52525b",
    fontSize: "11px",
    fontWeight: "500",
    textTransform: "uppercase",
    letterSpacing: "0.05em",
  },
  securityBadge: {
    display: "flex",
    alignItems: "center",
    justifyContent: "center",
    gap: "8px",
    color: "#52525b",
    fontSize: "12px",
  },
  shieldIcon: {
    width: "14px",
    height: "14px",
    color: "#10b981",
  },
  footer: {
    display: "flex",
    alignItems: "center",
    justifyContent: "center",
    gap: "8px",
    marginTop: "32px",
    color: "#52525b",
    fontSize: "12px",
  },
  footerText: {
    color: "#52525b",
  },
  footerDot: {
    color: "#3f3f46",
  },
  footerLink: {
    color: "#71717a",
    textDecoration: "none",
    transition: "color 0.2s ease",
  },
};
