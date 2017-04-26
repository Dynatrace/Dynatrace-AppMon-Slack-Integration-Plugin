package com.dynatrace;

import com.dynatrace.diagnostics.pdk.ActionEnvironment;
import com.dynatrace.diagnostics.pdk.ActionV2;
import com.dynatrace.diagnostics.pdk.Incident;
import com.dynatrace.diagnostics.pdk.Status;
import com.dynatrace.diagnostics.pdk.Violation;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collection;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.net.URLEncoder.encode;
import static java.util.logging.Logger.getLogger;

public class SlackChat implements ActionV2 {

	private static final Logger logger = getLogger(SlackChat.class.getName());

	private static final Character NEWLINE = '\n';

	@Override
	public Status setup(ActionEnvironment env) throws Exception {
		return new Status(Status.StatusCode.Success);
	}

	/**
	 * Executes the Action Plugin to process incidents.
	 * <p>
	 * <p>
	 * This method may be called at the scheduled intervals, but only if incidents
	 * occurred in the meantime. If the Plugin execution takes longer than the
	 * schedule interval, subsequent calls to
	 * {@link #execute(ActionEnvironment)} will be skipped until this method
	 * returns. After the execution duration exceeds the schedule timeout,
	 * {@link ActionEnvironment#isStopped()} will return <tt>true</tt>. In this
	 * case execution should be stopped as soon as possible. If the Plugin
	 * ignores {@link ActionEnvironment#isStopped()} or fails to stop execution in
	 * a reasonable timeframe, the execution thread will be stopped ungracefully
	 * which might lead to resource leaks!
	 *
	 * @param env a <tt>ActionEnvironment</tt> object that contains the Plugin
	 *            configuration and incidents
	 * @return a <tt>Status</tt> object that describes the result of the
	 * method call
	 */
	@Override
	public Status execute(ActionEnvironment env) throws Exception {
		Collection<Incident> incidents = env.getIncidents();

		int nbFailures = 0;
		for (Incident incident : incidents) {
			final boolean notifyAll = env.getConfigBoolean("notifyAll");
			final String dashboardLink = env.getConfigString("linkedDashboard");
			final URL channelUrl = env.getConfigUrl("url");

			String slackMessage = getSlackMessage(incident, notifyAll, dashboardLink);

			Status.StatusCode code = sendMessage(channelUrl, slackMessage);
			if (code != Status.StatusCode.Success) {
				nbFailures++;
			}
		}

		return new Status(getStatusCode(nbFailures, incidents.size()));
	}

	private Status.StatusCode getStatusCode(int nbFailures, int nbIncidents) {
		if (nbFailures == 0) {
			return Status.StatusCode.Success;
		}

		if (nbFailures != nbIncidents) {
			return Status.StatusCode.PartialSuccess;
		}

		return Status.StatusCode.ErrorInternalException;
	}

	private Status.StatusCode sendMessage(URL channelUrl, String message) throws IOException {
		final byte[] payload = message.getBytes();

		Status.StatusCode code = Status.StatusCode.Success;

		HttpURLConnection con = (HttpURLConnection) channelUrl.openConnection();
		con.setRequestMethod("POST");
		con.setConnectTimeout(5000);
		con.setReadTimeout(20000);

		con.setFixedLengthStreamingMode(payload.length);
		con.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
		con.setDoOutput(true);

		try {
			logger.log(Level.FINE, "Trying to get output stream...");

			OutputStream channel = con.getOutputStream();
			channel.write(payload);
			channel.close();

			int responseCode = con.getResponseCode();
			if (responseCode != 200) {
				code = Status.StatusCode.PartialSuccess;
			}

			logger.log(Level.FINE, "Response Code : %d", responseCode);
		} catch (IOException e) {
			code = Status.StatusCode.ErrorInternalException;
			logger.log(Level.SEVERE, "Exception thrown while writing to output stream...", e);
		} finally {
			con.disconnect();
		}

		return code;
	}

	@SuppressWarnings("unchecked")
	private String getSlackMessage(Incident incident, boolean notifyAll, String dashboardLink) throws UnsupportedEncodingException {
		JSONObject attachment = new JSONObject();
		attachment.put("title", getTitle(incident));
		attachment.put("color", getSeverityColor(incident));
		attachment.put("text", getMessage(incident));
		if (!(dashboardLink == null || "".equals(dashboardLink) || dashboardLink.isEmpty())) {
			attachment.put("title_link", "http://" + incident.getServerName() + "/rest/management/reports/create/" + encode(dashboardLink, "UTF-8").replaceAll("\\+", "%20"));
		}

		JSONArray attachArray = new JSONArray();
		attachArray.add(attachment);

		JSONObject jsonObj = new JSONObject();
		jsonObj.put("username", "dynatrace");
		jsonObj.put("icon_url", "https://media.glassdoor.com/sqll/309684/dynatrace-squarelogo-1458744847928.png");
		jsonObj.put("text", getState(incident, notifyAll));
		jsonObj.put("attachments", attachArray);

		return jsonObj.toJSONString();
	}

	private String getTitle(Incident incident) {
		return incident.getIncidentRule().getName();
	}

	private String getState(Incident incident, boolean notifyAll) {
		String state = "";

		if (notifyAll) {
			state = "<!channel> ";
		}

		if (incident.isOpen()) {
			state = state + "Dynatrace incident triggered:";
		} else if (incident.isClosed()) {
			state = state + "Dynatrace incident ended:";
		}

		return state;
	}

	private String getMessage(Incident incident) {
		StringBuilder message = new StringBuilder();

		message.append("Incident start: ").append(incident.getStartTime()).append(NEWLINE);
		message.append("Incident end: ").append(incident.getEndTime()).append(NEWLINE);
		message.append("Message: ").append(incident.getMessage()).append(NEWLINE);

		for (Violation violation : incident.getViolations()) {
			message.append("Violated Measure: ").append(violation.getViolatedMeasure().getName()).append(" - Threshold: ").append(violation.getViolatedThreshold().getValue()).append(NEWLINE);
		}

		return message.toString();
	}

	private String getSeverityColor(Incident incident) {
		String color = "good";

		if (incident.isOpen()) {
			String severity = incident.getSeverity().toString();
			switch (severity) {
				case "Error":
					color = "danger";
					break;
				case "Warning":
					color = "warning";
					break;
				case "Informational":
					color = "#439FE0";
					break;
				default:
					color = "good";
			}
		}

		return color;
	}

	@Override
	public void teardown(ActionEnvironment env) throws Exception {
		// not doing anything here...
	}
}
