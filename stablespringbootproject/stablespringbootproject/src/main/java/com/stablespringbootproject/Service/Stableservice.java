package com.stablespringbootproject.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import java.util.*;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import com.stablespringbootproject.Dto.Stablerequest;
import com.stablespringbootproject.Dto.Stableresponse;
import com.stablespringbootproject.Dto.VendorRegisterRequest;
import com.stablespringbootproject.Entity.*;
import com.stablespringbootproject.repository.*;

@Service
public class Stableservice {

	private final RestTemplate restTemplate;
	private final Countryrepo countryRepo;
	private final Countryservicerepo stableRepo;
	private final Vendorrepo vendorRepo;
	private final Vendorapirepo vendorApiRepository;
	private final VendorJsonMappingrepo jsonMappingRepo;
	private final Vehiclerequestmappingrepo vehicleRequestMappingRepo;

	public Stableservice(RestTemplate restTemplate, Countryrepo countryRepo, Countryservicerepo stableRepo,
			Vendorrepo vendorRepo, Vendorapirepo vendorApiRepository, VendorJsonMappingrepo jsonMappingRepo,
			Vehiclerequestmappingrepo vehicleRequestMappingRepo) {
		this.restTemplate = restTemplate;
		this.countryRepo = countryRepo;
		this.stableRepo = stableRepo;
		this.vendorRepo = vendorRepo;
		this.vendorApiRepository = vendorApiRepository;
		this.jsonMappingRepo = jsonMappingRepo;
		this.vehicleRequestMappingRepo = vehicleRequestMappingRepo;
	}

	public Stableresponse fetchVehicle(Stablerequest request) {

		// 1. Check Country (Row: Country ID 1)
		Countryentity country = countryRepo.findByCountryCode(request.getCountry())
				.orElseThrow(() -> new RuntimeException("Country Not Found"));

		// 2. Fetch single active service directly using the country code already resolved above
		Countryserviceentity service = stableRepo
				.findFirstByCountryCodeAndActiveTrue(country.getCountryCode())
				.orElseThrow(() -> new RuntimeException("No active service found for country: " + country.getCountryCode()));

		// 3. Resolve Vendor
		String resolvedVendorName = firstNonBlank(request.getVendorname());
		Vendorentity vendor = vendorRepo.findByVendorNameIgnoreCaseAndPhoneNumber(resolvedVendorName,
				request.getPhone_number());

		if (vendor == null) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Vendor Not Found");
		}

		// 4. Check API Config
		List<Vendorapis> vendorApis = vendorApiRepository.findByVendorIdAndApiType(vendor.getId(),
				request.getApi_usage_type());

		for (Vendorapis vendorApi : vendorApis) {

			// 5. Get Request Mappings
			List<vehiclerequestmapping> mappings = vehicleRequestMappingRepo.findByVendorIdAndApiId(vendor.getId(),
					vendorApi.getApiId());

			// Call Vendor
			Map<String, Object> vendorResponse = callVendor(service, vendorApi, mappings, request);

			if (vendorResponse != null) {

				// 6. Get Response Mappings
				List<Vehicleresponcemapping> responseMappings = jsonMappingRepo.findByApiId(vendorApi.getApiId());

				Stableresponse response = mapVendorResponse(vendorResponse, responseMappings);

				response.setCountry(country.getCountryCode());

				// If user passed specific fields, return only those
				if (request.getFields() != null && !request.getFields().isEmpty()) {
					Map<String, String> filtered = new HashMap<>();
					for (String field : request.getFields()) {
						if (response.getVehicleDetails().containsKey(field)) {
							filtered.put(field, response.getVehicleDetails().get(field));
						}
					}
					response.setVehicleDetails(filtered);
				}

				return response;
			}
		}

		throw new RuntimeException("Vehicle not found");
	}

	private static String firstNonBlank(String... values) {
		if (values == null)
			return null;
		for (String v : values) {
			if (v != null && !v.trim().isEmpty()) {
				return v.trim();
			}
		}
		return null;
	}

	private Map<String, Object> callVendor(Countryserviceentity service, Vendorapis vendorApi,
			List<vehiclerequestmapping> mappings, Stablerequest request) {

		String url = service.getBaseUrl() + vendorApi.getApiUrl();

		Map<String, String> requestMap = convertRequestToMap(request);

		Map<String, String> pathVars = new HashMap<>();
		Map<String, String> queryParams = new HashMap<>();
		Map<String, String> headersMap = new HashMap<>();
		Map<String, Object> bodyJson = new HashMap<>();

		for (vehiclerequestmapping m : mappings) {

			// LOGIC FOR ROW 10 vs ROW 11
			// Row 10: stable_field="vin", constant_value=null -> Takes value from User
			// Row 11: stable_field=null, constant_value="XYZ-SECRET-KEY" -> Takes static
			// value
			System.out.println(requestMap);
			String value = m.getConstantValue() != null && !m.getConstantValue().equalsIgnoreCase("")
					? m.getConstantValue()
					: getIgnoreCase(requestMap, m.getStableField());

			if (value == null)
				continue;

			switch (m.getLocation()) {

			case PATH:
				// Row 10 logic: puts VIN into {vehicleId}
				pathVars.put(m.getExternalName(), value);
				break;

			case QUERY:
				queryParams.put(m.getExternalName(), value);
				break;

			case HEADER:
				// Row 11 logic: puts "XYZ-SECRET-KEY" into header "apiKey"
				headersMap.put(m.getExternalName(), value);
				break;

			case BODY_JSON:
				bodyJson.put(m.getExternalName(), value);
				break;
			}
		}

		url = resolveUrl(url, pathVars);

		if (!queryParams.isEmpty()) {
			UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(url);
			queryParams.forEach(builder::queryParam);
			url = builder.toUriString();
		}

		HttpHeaders headers = new HttpHeaders();
		headersMap.forEach(headers::add);

		if (vendorApi.getContentType() != null) {
			headers.setContentType(MediaType.parseMediaType(vendorApi.getContentType()));
		}

		HttpMethod method = resolveHttpMethod(vendorApi.getHttpMethod());

		HttpEntity<?> entity = method == HttpMethod.GET ? new HttpEntity<>(headers)
				: new HttpEntity<>(bodyJson, headers);

		System.out.println("Calling Vendor URL : " + url);

		ResponseEntity<Map> response = restTemplate.exchange(URI.create(url), method, entity, Map.class);

		return response.getBody();
	}

	/*
	 * ------------------------------------------------ URL VARIABLE RESOLVER
	 * ------------------------------------------------
	 */

	private String resolveUrl(String url, Map<String, String> pathVars) {

		Pattern pattern = Pattern.compile("\\{([^}]+)\\}");
		Matcher matcher = pattern.matcher(url);

		StringBuffer result = new StringBuffer();

		while (matcher.find()) {

			String key = matcher.group(1);

			String value = getIgnoreCase(pathVars, key);

			if (value == null) {
				throw new RuntimeException("Missing path variable : " + key);
			}

			matcher.appendReplacement(result, URLEncoder.encode(value, StandardCharsets.UTF_8));
		}

		matcher.appendTail(result);

		return result.toString();
	}

	/*
	 * ------------------------------------------------ RESPONSE MAPPING (DYNAMIC -
	 * Row 20 Logic) Iterates over columns: vehiclenumber, make, model, year
	 * ------------------------------------------------
	 */

	private Stableresponse mapVendorResponse(Map<String, Object> vendorResponse,
			List<Vehicleresponcemapping> mappings) {

		Stableresponse response = new Stableresponse();
		Map<String, String> vehicleDetails = new HashMap<>();

		if (mappings.isEmpty())
			return response;

		Vehicleresponcemapping map = mappings.get(0);

		try {
			// Iterate over columns of the mapping table: vehiclenumber, make, model, year
			for (Field field : map.getClass().getDeclaredFields()) {

				// Skip ID fields (not data mappings)
				if (field.getName().equalsIgnoreCase("id") || field.getName().equalsIgnoreCase("apiId")
						|| field.getName().equalsIgnoreCase("vendorId")
						|| field.getName().equalsIgnoreCase("countryId")) {
					continue;
				}

				field.setAccessible(true);

				// 1. Internal Key = Column Name (e.g., "make")
				String internalKey = field.getName();

				// 2. External Key = Value in Table Cell (e.g., "car_maker")
				Object externalKeyObj = field.get(map);

				if (externalKeyObj != null) {
					String externalKey = externalKeyObj.toString();

					// 3. Get Value from Vendor JSON (e.g., "Toyota")
					Object value = vendorResponse.get(externalKey);

					if (value != null) {
						// 4. Put into Map: ("make", "Toyota")
						vehicleDetails.put(internalKey, value.toString());
					}
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}

		response.setVehicleDetails(vehicleDetails);

		return response;
	}

	/*
	 * ------------------------------------------------ UTIL METHODS
	 * ------------------------------------------------
	 */

	private HttpMethod resolveHttpMethod(String method) {
		try {
			return HttpMethod.valueOf(method.toUpperCase());
		} catch (Exception e) {
			return HttpMethod.GET;
		}
	}

	private Map<String, String> convertRequestToMap(Object obj) {
		Map<String, String> map = new HashMap<>();
		if (obj == null) {
			return map;
		}
		try {
			for (Field field : obj.getClass().getDeclaredFields()) {
				field.setAccessible(true);
				Object value = field.get(obj);
				if (value != null) {
					map.put(field.getName(), value.toString());
				}
			}
		} catch (Exception e) {
			throw new RuntimeException("Error converting request to map", e);
		}
		return map;
	}

	private String getIgnoreCase(Map<String, String> map, String key) {
		for (Map.Entry<String, String> entry : map.entrySet()) {
			if (entry.getKey().equalsIgnoreCase(key)) {
				return entry.getValue();
			}
		}
		return null;
	}

	/*
	 * ------------------------------------------------
	 * POST /stable/vendor/register
	 * Registers a brand new vendor with API config and response mapping
	 * ------------------------------------------------
	 */
	public String registerVendor(VendorRegisterRequest request) {

		// 1. Validate Country
		Countryentity country = countryRepo.findByCountryCode(request.getCountryCode())
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
						"Country Not Found for code: " + request.getCountryCode()));

		// 2. Make sure vendor does NOT already exist
		Vendorentity existing = vendorRepo.findByVendorNameIgnoreCaseAndPhoneNumber(
				request.getVendorName(), request.getVendorPhone());

		if (existing != null) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"Vendor already exists. Use the update API to modify.");
		}

		// 3. Create new vendor
		Vendorentity vendor = new Vendorentity();
		vendor.setVendorName(request.getVendorName());
		vendor.setPhoneNumber(request.getVendorPhone());
		vendor.setActive(true);
		Vendorentity savedVendor = vendorRepo.save(vendor);

		// 4. Create new API config
		Vendorapis vendorApi = new Vendorapis();
		vendorApi.setVendorId(savedVendor.getId());
		vendorApi.setApiUrl(request.getApiUrl());
		vendorApi.setApiType(request.getApiUsageType());
		Vendorapis savedApi = vendorApiRepository.save(vendorApi);

		// 5. Save response mapping
		Vehicleresponcemapping mapping = new Vehicleresponcemapping();
		mapping.setCountryId(country.getId());
		mapping.setVendorId(savedVendor.getId());
		mapping.setApiId(savedApi.getApiId());
		mapping.setVehiclenumber(request.getVehicleNumberField());
		mapping.setMake(request.getMakeField());
		mapping.setModel(request.getModelField());
		mapping.setYear(request.getYearField());
		jsonMappingRepo.save(mapping);

		// 6. Register country service
		Countryserviceentity service = new Countryserviceentity();
		service.setCountryCode(request.getCountryCode());
		service.setBaseUrl(request.getBaseUrl());
		service.setActive(true);
		stableRepo.save(service);

		return "New Vendor Registered Successfully";
	}

	/*
	 * ------------------------------------------------
	 * PUT /stable/vendor/update
	 * Updates API config and response mapping for an existing vendor
	 * ------------------------------------------------
	 */
	public String updateVendor(VendorRegisterRequest request) {

		// 1. Validate Country
		countryRepo.findByCountryCode(request.getCountryCode())
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
						"Country Not Found for code: " + request.getCountryCode()));

		// 2. Make sure vendor DOES exist
		Vendorentity vendor = vendorRepo.findByVendorNameIgnoreCaseAndPhoneNumber(
				request.getVendorName(), request.getVendorPhone());

		if (vendor == null) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND,
					"Vendor not found. Use the register API to create a new vendor.");
		}

		// 3. Update API config
		List<Vendorapis> existingApis = vendorApiRepository.findByVendorIdAndApiType(
				vendor.getId(), request.getApiUsageType());

		if (existingApis == null || existingApis.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND,
					"No API config found for this vendor and type. Register first.");
		}

		Vendorapis vendorApi = existingApis.get(0);
		vendorApi.setApiUrl(request.getApiUrl());
		Vendorapis savedApi = vendorApiRepository.save(vendorApi);

		// 4. Update response mapping
		List<Vehicleresponcemapping> existingMappings = jsonMappingRepo.findByApiId(savedApi.getApiId());

		Vehicleresponcemapping mapping = existingMappings.isEmpty()
				? new Vehicleresponcemapping()
				: existingMappings.get(0);

		mapping.setVehiclenumber(request.getVehicleNumberField());
		mapping.setMake(request.getMakeField());
		mapping.setModel(request.getModelField());
		mapping.setYear(request.getYearField());
		jsonMappingRepo.save(mapping);

		return "Vendor Configuration Updated Successfully";
	}

	/*
	 * ------------------------------------------------
	 * POST /stable/vendor/country
	 * Registers a new country service (base URL for a country)
	 * ------------------------------------------------
	 */
	public String registerCountryService(VendorRegisterRequest request) {

		// 1. Validate Country
		countryRepo.findByCountryCode(request.getCountryCode())
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
						"Country Not Found for code: " + request.getCountryCode()));

		// 2. Save country service
		Countryserviceentity service = new Countryserviceentity();
		service.setCountryCode(request.getCountryCode());
		service.setBaseUrl(request.getBaseUrl());
		service.setActive(true);
		stableRepo.save(service);

		return "Country Service Registered Successfully for: " + request.getCountryCode();
	}
}