import React, { Component } from "react";
import { LiveStatTiles } from "../components/LiveStatTiles";
import { ClusterTiles } from "../components/ClusterTiles";
import { dynamicTitleContent } from "../components/DynamicTitleSignal";

export class HomePage extends Component {
  componentDidMount() {
    dynamicTitleContent.value = undefined;
  }

  render() {
    const { env, usedNewEngine } = this.props;

    return (
      <div className="dashboard-root">
        {/* Ambient background effects */}
        <div className="dashboard-ambient">
          <div className="ambient-orb ambient-orb--primary" />
          <div className="ambient-orb ambient-orb--secondary" />
        </div>

        <div className="dashboard-content">
          {/* Hero Section */}
          <header className="dashboard-hero">
            <div className="hero-brand">
              {env && (
                <img
                  src={env.otoroshiLogo}
                  className="hero-logo"
                  alt="Otoroshi"
                />
              )}
              <div className="hero-text">
                <h1 className="hero-title">Otoroshi</h1>
                <p className="hero-subtitle">Cloud-native API Gateway</p>
              </div>
            </div>

            {/* Status indicators */}
            <div className="hero-status">
              <div className="status-pill status-pill--active">
                <span className="status-dot" />
                <span>System Online</span>
              </div>
            </div>
          </header>

          {/* Legacy Engine Warning */}
          {!usedNewEngine && (
            <div className="engine-banner">
              <div className="banner-icon">
                <svg
                  width="20"
                  height="20"
                  viewBox="0 0 20 20"
                  fill="currentColor"
                >
                  <path
                    fillRule="evenodd"
                    d="M8.257 3.099c.765-1.36 2.722-1.36 3.486 0l5.58 9.92c.75 1.334-.213 2.98-1.742 2.98H4.42c-1.53 0-2.493-1.646-1.743-2.98l5.58-9.92zM11 13a1 1 0 11-2 0 1 1 0 012 0zm-1-8a1 1 0 00-1 1v3a1 1 0 002 0V6a1 1 0 00-1-1z"
                    clipRule="evenodd"
                  />
                </svg>
              </div>
              <div className="banner-content">
                <strong>Legacy Engine Active</strong>
                <span>
                  The new Otoroshi engine is ready for production use.
                </span>
              </div>
              <a
                href="https://maif.github.io/otoroshi/manual/next/engine.html"
                target="_blank"
                rel="noopener noreferrer"
                className="banner-action"
              >
                Upgrade now
                <svg
                  width="16"
                  height="16"
                  viewBox="0 0 20 20"
                  fill="currentColor"
                >
                  <path
                    fillRule="evenodd"
                    d="M10.293 3.293a1 1 0 011.414 0l6 6a1 1 0 010 1.414l-6 6a1 1 0 01-1.414-1.414L14.586 11H3a1 1 0 110-2h11.586l-4.293-4.293a1 1 0 010-1.414z"
                    clipRule="evenodd"
                  />
                </svg>
              </a>
            </div>
          )}

          {/* Metrics Grid */}
          <section className="metrics-section">
            <LiveStatTiles url="/bo/api/proxy/api/live/global?every=2000" />
          </section>

          {/* Cluster Section */}
          <section className="cluster-section">
            <ClusterTiles
              url="/bo/api/proxy/api/cluster/live?every=2000"
              env={env}
            />
          </section>
        </div>
      </div>
    );
  }
}
