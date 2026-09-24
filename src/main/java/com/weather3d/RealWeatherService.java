package com.weather3d;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.weather3d.conditions.Biome;
import com.weather3d.conditions.Weather;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Real-life weather for 3D-WeatherIRL.
 *
 * Everything for the "Real Weather" mode lives in this file so the upstream 3D Weather
 * code stays almost untouched, which keeps GitHub's "Sync fork" conflict-free.
 *
 * Data comes from Open-Meteo (https://open-meteo.com): free for non-commercial use, no API key.
 * All network calls are asynchronous (OkHttp's own threads), never on the client thread.
 */
@Singleton
public class RealWeatherService
{
	private static final Logger log = LoggerFactory.getLogger(RealWeatherService.class);

	private static final HttpUrl GEOCODING_URL = HttpUrl.get("https://geocoding-api.open-meteo.com/v1/search");
	private static final HttpUrl FORECAST_URL = HttpUrl.get("https://api.open-meteo.com/v1/forecast");

	private static final long RETRY_DELAY_MS = TimeUnit.MINUTES.toMillis(2);
	private static final int MIN_REFRESH_MINUTES = 1;

	private static final Pattern LAT_LON = Pattern.compile("^\\s*(-?\\d{1,2}(?:\\.\\d+)?)\\s*,\\s*(-?\\d{1,3}(?:\\.\\d+)?)\\s*$");
	private static final Pattern US_ZIP = Pattern.compile("^\\d{5}$");
	private static final Map<String, String> US_STATES = new HashMap<>();

	static
	{
		String[][] states = {
			{"AL", "Alabama"}, {"AK", "Alaska"}, {"AZ", "Arizona"}, {"AR", "Arkansas"}, {"CA", "California"},
			{"CO", "Colorado"}, {"CT", "Connecticut"}, {"DE", "Delaware"}, {"DC", "District of Columbia"},
			{"FL", "Florida"}, {"GA", "Georgia"}, {"HI", "Hawaii"}, {"ID", "Idaho"}, {"IL", "Illinois"},
			{"IN", "Indiana"}, {"IA", "Iowa"}, {"KS", "Kansas"}, {"KY", "Kentucky"}, {"LA", "Louisiana"},
			{"ME", "Maine"}, {"MD", "Maryland"}, {"MA", "Massachusetts"}, {"MI", "Michigan"}, {"MN", "Minnesota"},
			{"MS", "Mississippi"}, {"MO", "Missouri"}, {"MT", "Montana"}, {"NE", "Nebraska"}, {"NV", "Nevada"},
			{"NH", "New Hampshire"}, {"NJ", "New Jersey"}, {"NM", "New Mexico"}, {"NY", "New York"},
			{"NC", "North Carolina"}, {"ND", "North Dakota"}, {"OH", "Ohio"}, {"OK", "Oklahoma"}, {"OR", "Oregon"},
			{"PA", "Pennsylvania"}, {"RI", "Rhode Island"}, {"SC", "South Carolina"}, {"SD", "South Dakota"},
			{"TN", "Tennessee"}, {"TX", "Texas"}, {"UT", "Utah"}, {"VT", "Vermont"}, {"VA", "Virginia"},
			{"WA", "Washington"}, {"WV", "West Virginia"}, {"WI", "Wisconsin"}, {"WY", "Wyoming"},
			{"PR", "Puerto Rico"}
		};
		for (String[] state : states)
		{
			US_STATES.put(state[0], state[1]);
		}
	}

	/** One reading from the API. Stored raw so config toggles (e.g. stars at night) apply instantly. */
	private static final class Observation
	{
		private final String location;
		private final int weatherCode;
		private final boolean isDay;

		private Observation(String location, int weatherCode, boolean isDay)
		{
			this.location = location;
			this.weatherCode = weatherCode;
			this.isDay = isDay;
		}
	}

	private final OkHttpClient okHttpClient;
	private final Gson gson;
	private final CyclesConfig config;

	// Written from OkHttp threads, read from the client thread
	private volatile String activeLocation = "";
	private volatile double[] coordinates;
	private volatile Observation latest;
	private volatile long nextAttemptAtMs;
	private volatile boolean requestInFlight;

	@Inject
	RealWeatherService(OkHttpClient okHttpClient, Gson gson, CyclesConfig config)
	{
		this.okHttpClient = okHttpClient;
		this.gson = gson;
		this.config = config;
	}

	/**
	 * Called every game tick while Weather Type is Real Weather. Cheap: it only starts a
	 * request when the refresh interval has passed or the Location setting changed.
	 */
	public void refreshIfDue()
	{
		String location = config.realWeatherLocation() == null ? "" : config.realWeatherLocation().trim();

		if (!location.equals(activeLocation))
		{
			// New location: look it up right away. The previous weather stays on screen until
			// the new location's weather arrives, so there's no flash of clear skies in between.
			activeLocation = location;
			coordinates = null;
			nextAttemptAtMs = 0;
			if (location.isEmpty())
			{
				latest = null;
			}
		}

		if (location.isEmpty() || requestInFlight || System.currentTimeMillis() < nextAttemptAtMs)
		{
			return;
		}

		if (coordinates == null)
		{
			coordinates = parseCoordinates(location);
		}

		requestInFlight = true;
		nextAttemptAtMs = System.currentTimeMillis() + RETRY_DELAY_MS; // pushed further out on success

		if (coordinates == null)
		{
			requestGeocode(location);
		}
		else
		{
			requestCurrentWeather(location, coordinates);
		}
	}

	/**
	 * The Weather to show right now, or null if the plugin's normal forecast should be used
	 * instead (caves / otherworldly areas, when that option is on).
	 */
	public Weather resolveWeather(Biome biome)
	{
		if (config.realWeatherKeepSpecialBiomes()
				&& (biome == Biome.CAVE || biome == Biome.LAVA_CAVE || biome == Biome.COSMOS))
		{
			return null;
		}

		Observation observation = latest;
		if (observation == null)
		{
			return Weather.SUNNY; // no data yet (or no Location set)
		}

		Weather weather = mapWeatherCode(observation.weatherCode, observation.isDay, config.realWeatherStarsAtNight());
		return weather != null ? weather : Weather.SUNNY;
	}

	/**
	 * Maps a WMO weather code (used by Open-Meteo) to one of the plugin's Weather types.
	 * https://open-meteo.com/en/docs (see "WMO Weather interpretation codes")
	 */
	static Weather mapWeatherCode(int code, boolean isDay, boolean starsAtNight)
	{
		switch (code)
		{
			case 0:  // clear sky
			case 1:  // mainly clear
				return (!isDay && starsAtNight) ? Weather.STARRY : Weather.SUNNY;
			case 2:  // partly cloudy
				return Weather.PARTLY_CLOUDY;
			case 3:  // overcast
				return Weather.CLOUDY;
			case 45: // fog
			case 48: // depositing rime fog
				return Weather.FOGGY;
			case 51: case 53: case 55: // drizzle
			case 56: case 57:          // freezing drizzle
			case 61: case 63: case 65: // rain
			case 66: case 67:          // freezing rain
			case 80: case 81: case 82: // rain showers
				return Weather.RAINY;
			case 71: case 73: case 75: // snowfall
			case 77:                   // snow grains
			case 85: case 86:          // snow showers
				return Weather.SNOWY;
			case 95:                   // thunderstorm
			case 96: case 99:          // thunderstorm with hail
				return Weather.STORMY;
			default:
				return null;
		}
	}

	/** Human-readable WMO code, for the log. */
	static String describeWeatherCode(int code)
	{
		switch (code)
		{
			case 0: return "clear sky";
			case 1: return "mainly clear";
			case 2: return "partly cloudy";
			case 3: return "overcast";
			case 45: return "fog";
			case 48: return "rime fog";
			case 51: case 53: case 55: return "drizzle";
			case 56: case 57: return "freezing drizzle";
			case 61: return "light rain";
			case 63: return "rain";
			case 65: return "heavy rain";
			case 66: case 67: return "freezing rain";
			case 71: case 73: case 75: return "snow";
			case 77: return "snow grains";
			case 80: case 81: case 82: return "rain showers";
			case 85: case 86: return "snow showers";
			case 95: return "thunderstorm";
			case 96: case 99: return "thunderstorm with hail";
			default: return "unknown";
		}
	}

	private void requestGeocode(String location)
	{
		String name = location;
		String qualifier = "";
		int comma = location.indexOf(',');
		if (comma >= 0)
		{
			name = location.substring(0, comma).trim();
			qualifier = location.substring(comma + 1).trim();
		}

		// A bare 5-digit ZIP is searched as "12345, US" so it can't match a European postal code
		String searchName = US_ZIP.matcher(name).matches() && qualifier.isEmpty() ? name + ", US" : name;

		HttpUrl url = GEOCODING_URL.newBuilder()
				.addQueryParameter("name", searchName)
				.addQueryParameter("count", "10")
				.addQueryParameter("language", "en")
				.addQueryParameter("format", "json")
				.build();

		final String finalQualifier = qualifier;
		get(url, location, json ->
		{
			JsonArray results = json.has("results") && json.get("results").isJsonArray() ? json.getAsJsonArray("results") : null;
			if (results == null || results.size() == 0)
			{
				log.warn("Real Weather: couldn't find a place called \"{}\". Try \"City, ST\" or \"latitude, longitude\".", location);
				clearPreviousLocationWeather();
				nextAttemptAtMs = System.currentTimeMillis() + refreshIntervalMs();
				return;
			}

			JsonObject place = pickPlace(results, finalQualifier);
			double[] found = new double[]{place.get("latitude").getAsDouble(), place.get("longitude").getAsDouble()};
			coordinates = found;
			log.info("Real Weather: \"{}\" resolved to {}, {} ({}, {})", location,
					getString(place, "name"), getString(place, "admin1"), found[0], found[1]);
			requestCurrentWeather(location, found); // straight on to the weather, no waiting for a tick
		});
	}

	private void requestCurrentWeather(String location, double[] coords)
	{
		HttpUrl url = FORECAST_URL.newBuilder()
				.addQueryParameter("latitude", String.valueOf(coords[0]))
				.addQueryParameter("longitude", String.valueOf(coords[1]))
				.addQueryParameter("current", "weather_code,is_day")
				.addQueryParameter("timezone", "auto")
				.build();

		get(url, location, json ->
		{
			JsonObject current = json.has("current") && json.get("current").isJsonObject() ? json.getAsJsonObject("current") : null;
			if (current == null || !current.has("weather_code") || current.get("weather_code").isJsonNull())
			{
				log.warn("Real Weather: weather response had no current conditions");
				clearPreviousLocationWeather();
				return;
			}

			int code = current.get("weather_code").getAsInt();
			boolean isDay = !current.has("is_day") || current.get("is_day").isJsonNull() || current.get("is_day").getAsInt() == 1;
			latest = new Observation(location, code, isDay);
			nextAttemptAtMs = System.currentTimeMillis() + refreshIntervalMs();
			Weather shown = mapWeatherCode(code, isDay, config.realWeatherStarsAtNight());
			log.info("Real Weather: {} reports \"{}\" (code {}, {}) -> showing {}", location, describeWeatherCode(code), code,
					isDay ? "day" : "night", shown != null ? shown.getName() : "Clear");
		});
	}

	private void get(HttpUrl url, String location, Consumer<JsonObject> onSuccess)
	{
		Request request = new Request.Builder().url(url).build();
		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.warn("Real Weather: request failed ({})", e.getMessage());
				if (location.equals(activeLocation))
				{
					clearPreviousLocationWeather();
				}
				requestInFlight = false;
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (!location.equals(activeLocation))
					{
						return; // Location setting changed while this was in flight
					}

					ResponseBody body = r.body();
					if (!r.isSuccessful() || body == null)
					{
						log.warn("Real Weather: HTTP {} from {}", r.code(), url.host());
						clearPreviousLocationWeather();
						return;
					}

					JsonObject json = gson.fromJson(body.charStream(), JsonObject.class);
					if (json != null)
					{
						onSuccess.accept(json);
					}
				}
				catch (RuntimeException e)
				{
					log.warn("Real Weather: couldn't read response from {}", url.host(), e);
					clearPreviousLocationWeather();
				}
				finally
				{
					requestInFlight = false;
				}
			}
		});
	}

	/**
	 * If the lookup for a newly entered location fails, stop showing the old location's weather.
	 * A failed routine refresh of the same location keeps the last known weather.
	 */
	private void clearPreviousLocationWeather()
	{
		Observation observation = latest;
		if (observation != null && !observation.location.equals(activeLocation))
		{
			latest = null;
		}
	}

	/** Picks the geocoding result matching the part after the comma ("WV", "West Virginia", "GB", ...). */
	private static JsonObject pickPlace(JsonArray results, String qualifier)
	{
		JsonObject first = results.get(0).getAsJsonObject();
		if (qualifier.isEmpty())
		{
			return first;
		}

		String[] tokens = qualifier.split(",");
		for (JsonElement element : results)
		{
			JsonObject place = element.getAsJsonObject();
			boolean allMatch = true;
			for (String token : tokens)
			{
				if (!placeMatches(place, token.trim()))
				{
					allMatch = false;
					break;
				}
			}
			if (allMatch)
			{
				return place;
			}
		}

		log.warn("Real Weather: no exact match for \"{}\", using {}, {}", qualifier, getString(first, "name"), getString(first, "admin1"));
		return first;
	}

	private static boolean placeMatches(JsonObject place, String token)
	{
		if (token.isEmpty())
		{
			return true;
		}

		String upper = token.toUpperCase(Locale.ROOT);
		String expanded = US_STATES.containsKey(upper) ? US_STATES.get(upper) : token;

		String[] fields = {"admin1", "admin2", "country", "country_code"};
		for (String field : fields)
		{
			String value = getString(place, field);
			if (value.equalsIgnoreCase(token) || value.equalsIgnoreCase(expanded))
			{
				return true;
			}
		}
		return false;
	}

	private static double[] parseCoordinates(String location)
	{
		Matcher matcher = LAT_LON.matcher(location);
		if (!matcher.matches())
		{
			return null;
		}

		double lat = Double.parseDouble(matcher.group(1));
		double lon = Double.parseDouble(matcher.group(2));
		if (lat < -90 || lat > 90 || lon < -180 || lon > 180)
		{
			return null;
		}
		return new double[]{lat, lon};
	}

	private static String getString(JsonObject object, String key)
	{
		JsonElement element = object.get(key);
		return element == null || element.isJsonNull() ? "" : element.getAsString();
	}

	private long refreshIntervalMs()
	{
		return TimeUnit.MINUTES.toMillis(Math.max(MIN_REFRESH_MINUTES, config.realWeatherRefreshMinutes()));
	}
}
