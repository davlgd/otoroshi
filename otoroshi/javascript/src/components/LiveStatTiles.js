import React, { Component } from "react";
import { converterBase2 } from "byte-converter";
import { Sparklines, SparklinesLine, SparklinesSpots } from "react-sparklines";

function init(size, value = 0) {
  const arr = [];
  for (let i = 0; i < size; i++) {
    arr.push(value);
  }
  return arr;
}

// Modern metric card with sparkline
class MetricCard extends Component {
  size = 40;

  state = {
    values: init(this.size),
  };

  restrict(what, size) {
    if (what.length > size) {
      what.shift();
    }
    return what;
  }

  componentWillReceiveProps(nextProps) {
    if (nextProps.time !== this.props.time) {
      let value = nextProps.value;
      if (value.replace) {
        value = value
          .replace(" ", "")
          .replace("in", "")
          .replace("out", "")
          .replace("/sec", "")
          .replace(/Mb|Gb|Tb|Pb|Kb/, "");
        value = parseFloat(value);
      }
      this.setState({
        values: this.restrict([...this.state.values, value], this.size),
      });
    }
  }

  render() {
    const { value, label, icon, color = "yellow", trend } = this.props;

    return (
      <div className={`stat-card stat-card--${color}`}>
        <div className="stat-card__header">
          <div className="stat-card__icon">{icon}</div>
          <span className="stat-card__label">{label}</span>
        </div>

        <div className="stat-card__body">
          <div className="stat-card__value">{value}</div>
          {trend && (
            <div
              className={`stat-card__trend stat-card__trend--${
                trend > 0 ? "up" : "down"
              }`}
            >
              {trend > 0 ? "↑" : "↓"} {Math.abs(trend)}%
            </div>
          )}
        </div>

        <div className="stat-card__sparkline">
          <Sparklines
            data={this.state.values}
            limit={this.state.values.length}
            height={50}
          >
            <SparklinesLine
              style={{ strokeWidth: 2, fill: "none" }}
              color="currentColor"
            />
            <SparklinesSpots size={2} style={{ fill: "currentColor" }} />
          </Sparklines>
        </div>

        <div className="stat-card__glow" />
      </div>
    );
  }
}

// Simple stat card without sparkline
const SimpleStatCard = ({ value, label, icon, color = "zinc" }) => (
  <div className={`stat-card stat-card--simple stat-card--${color}`}>
    <div className="stat-card__header">
      <div className="stat-card__icon">{icon}</div>
      <span className="stat-card__label">{label}</span>
    </div>
    <div className="stat-card__body">
      <div className="stat-card__value">{value}</div>
    </div>
  </div>
);

// Loading spinner
const LoadingSpinner = () => (
  <div className="metrics-loading">
    <div className="loading-spinner">
      <svg viewBox="0 0 50 50" className="spinner-svg">
        <circle
          cx="25"
          cy="25"
          r="20"
          fill="none"
          stroke="currentColor"
          strokeWidth="3"
          strokeLinecap="round"
          className="spinner-track"
        />
        <circle
          cx="25"
          cy="25"
          r="20"
          fill="none"
          stroke="var(--color-primary)"
          strokeWidth="3"
          strokeLinecap="round"
          strokeDasharray="80 200"
          className="spinner-head"
        />
      </svg>
    </div>
    <p className="loading-text">Connecting to metrics stream...</p>
  </div>
);

// Icons
const Icons = {
  speed: (
    <svg
      width="18"
      height="18"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
    >
      <path d="M13 2L3 14h9l-1 8 10-12h-9l1-8z" />
    </svg>
  ),
  clock: (
    <svg
      width="18"
      height="18"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
    >
      <circle cx="12" cy="12" r="10" />
      <path d="M12 6v6l4 2" />
    </svg>
  ),
  activity: (
    <svg
      width="18"
      height="18"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
    >
      <path d="M22 12h-4l-3 9L9 3l-3 9H2" />
    </svg>
  ),
  download: (
    <svg
      width="18"
      height="18"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
    >
      <path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4M7 10l5 5 5-5M12 15V3" />
    </svg>
  ),
  upload: (
    <svg
      width="18"
      height="18"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
    >
      <path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4M17 8l-5-5-5 5M12 3v12" />
    </svg>
  ),
  layers: (
    <svg
      width="18"
      height="18"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
    >
      <path d="M12 2L2 7l10 5 10-5-10-5zM2 17l10 5 10-5M2 12l10 5 10-5" />
    </svg>
  ),
  database: (
    <svg
      width="18"
      height="18"
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
  server: (
    <svg
      width="18"
      height="18"
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
};

export class LiveStatTiles extends Component {
  state = {
    firstDone: false,
    dataIn: "0 Kb in",
    dataOut: "0 Kb out",
    requests: 0,
    rate: 0.0,
    duration: 0.0,
    overhead: 0.0,
    dataRate: "0 Kb/sec",
    dataInRate: "0 Kb/sec in",
    dataOutRate: "0 Kb/sec out",
    concurrentProcessedRequests: 0,
    concurrentHandledRequests: 0,
  };

  componentDidMount() {
    this.evtSource = new EventSource(this.props.url);
    this.evtSource.onmessage = (e) => this.onMessage(e);
  }

  componentWillUnmount() {
    if (this.evtSource) {
      this.evtSource.close();
      delete this.evtSource;
    }
  }

  computeValue(value) {
    let unit = "Mb";
    let computedValue = parseFloat(converterBase2(value, "B", "MB").toFixed(3));
    if (computedValue > 1024.0) {
      computedValue = parseFloat(converterBase2(value, "B", "GB").toFixed(3));
      unit = "Gb";
    }
    if (computedValue > 1024.0) {
      computedValue = parseFloat(converterBase2(value, "B", "TB").toFixed(3));
      unit = "Tb";
    }
    if (computedValue > 1024.0) {
      computedValue = parseFloat(converterBase2(value, "B", "PB").toFixed(3));
      unit = "Pb";
    }
    return [computedValue, unit];
  }

  onMessage = (e) => {
    const data = JSON.parse(e.data);
    data.rate = data.rate || 0.0;
    data.duration = data.duration || 0.0;
    data.concurrentProcessedRequests = data.concurrentProcessedRequests || 0;
    data.concurrentHandledRequests = data.concurrentHandledRequests || 0;
    const [valueIn, unitIn] = this.computeValue(data.dataIn);
    const [valueOut, unitOut] = this.computeValue(data.dataOut);
    const [valueInRate, unitInRate] = this.computeValue(data.dataInRate);
    const [valueOutRate, unitOutRate] = this.computeValue(data.dataOutRate);
    this.setState({
      firstDone: true,
      dataIn: `${valueIn.prettify()} ${unitIn}`,
      dataOut: `${valueOut.prettify()} ${unitOut}`,
      requests: data.calls.prettify(),
      rate: parseFloat(data.rate.toFixed(2)).prettify(),
      duration: parseFloat(data.duration.toFixed(2)).prettify(),
      overhead: parseFloat(data.overhead.toFixed(2)).prettify(),
      concurrentProcessedRequests: data.concurrentProcessedRequests.prettify(),
      concurrentHandledRequests: data.concurrentHandledRequests.prettify(),
      dataInRate: `${valueInRate.prettify()} ${unitInRate}/s`,
      dataOutRate: `${valueOutRate.prettify()} ${unitOutRate}/s`,
      dataRate: `${parseFloat(
        (valueOutRate + valueInRate).toFixed(3)
      ).prettify()} ${unitOutRate}/sec`,
    });
  };

  render() {
    if (!this.state.firstDone) {
      return <LoadingSpinner />;
    }

    const now = Date.now();

    return (
      <div className="metrics-dashboard">
        {/* Section: Real-time Performance */}
        <div className="metrics-group">
          <div className="metrics-group__header">
            <h3 className="metrics-group__title">
              <span className="pulse-dot" />
              Real-time Performance
            </h3>
          </div>
          <div className="metrics-grid metrics-grid--3">
            <MetricCard
              time={now}
              value={this.state.rate}
              label="Requests/sec"
              icon={Icons.speed}
              color="yellow"
            />
            <MetricCard
              time={now}
              value={`${this.state.duration}ms`}
              label="Avg. Latency"
              icon={Icons.clock}
              color="blue"
            />
            <MetricCard
              time={now}
              value={`${this.state.overhead}ms`}
              label="Overhead"
              icon={Icons.activity}
              color="purple"
            />
          </div>
        </div>

        {/* Section: Data Transfer */}
        <div className="metrics-group">
          <div className="metrics-group__header">
            <h3 className="metrics-group__title">Data Transfer</h3>
          </div>
          <div className="metrics-grid metrics-grid--3">
            <MetricCard
              time={now}
              value={this.state.dataInRate}
              label="Inbound Rate"
              icon={Icons.download}
              color="green"
            />
            <MetricCard
              time={now}
              value={this.state.dataOutRate}
              label="Outbound Rate"
              icon={Icons.upload}
              color="cyan"
            />
            <MetricCard
              time={now}
              value={this.state.concurrentHandledRequests}
              label="Active Connections"
              icon={Icons.layers}
              color="orange"
            />
          </div>
        </div>

        {/* Section: Cumulative Stats */}
        <div className="metrics-group">
          <div className="metrics-group__header">
            <h3 className="metrics-group__title">Cumulative Statistics</h3>
          </div>
          <div className="metrics-grid metrics-grid--3">
            <SimpleStatCard
              value={this.state.requests}
              label="Total Requests"
              icon={Icons.server}
              color="zinc"
            />
            <SimpleStatCard
              value={this.state.dataIn}
              label="Total Inbound"
              icon={Icons.download}
              color="zinc"
            />
            <SimpleStatCard
              value={this.state.dataOut}
              label="Total Outbound"
              icon={Icons.upload}
              color="zinc"
            />
          </div>
        </div>
      </div>
    );
  }
}
