/**
 * 
 */
package spv.util.astrometry.net;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.stream.Collectors;

import org.apache.http.HttpEntity;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;

import com.google.gson.Gson;

import spv.util.PlateSolveResult;

/**
 * main class interfacing the astrometry.net services
 * 
 * @author Petros Pissias
 *
 */
public class AstrometryDotNet {

	private static final String loginURI = "http://nova.astrometry.net/api/login";
	
	private String sessionID; 
	
	private Gson gson;
	
	public AstrometryDotNet() {
		gson = new Gson();
	}


	/**
	 * Java 11
	 * @throws IOException
	 * @throws InterruptedException
	 */
	public void login() throws IOException, InterruptedException {
		//make call to astrometry.bet website
		HttpClient client = HttpClient.newBuilder()
				.version(Version.HTTP_2)
				.build();

		LoginRequest loginReq = new LoginRequest();
		loginReq.setApikey("XXXXXXXX");
		
		System.out.println("loginReq:"+gson.toJson(loginReq));
		
		var builder = new StringBuilder();
		builder.append(URLEncoder.encode("request-json", StandardCharsets.UTF_8));
		builder.append("=");
		builder.append(URLEncoder.encode(gson.toJson(loginReq), StandardCharsets.UTF_8));
		
		System.out.println("body of post:"+builder.toString());
		
		HttpRequest request = HttpRequest.newBuilder()
				.POST(HttpRequest.BodyPublishers.ofString(builder.toString()))
				.headers("Content-Type", "application/x-www-form-urlencoded")
				.uri(URI.create(loginURI))				
				.build();
		
		System.out.println("request body publisher:"+request.bodyPublisher().get().contentLength());
		System.out.println("request:"+request.toString());
		
		HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
		System.out.println("response body:"+response.body());
		System.out.println("response status code:"+response.statusCode());
		System.out.println("response header:"+response.headers());
		
		LoginResponse loginResponse = gson.fromJson(response.body(), LoginResponse.class);
		System.out.println("response:"+loginResponse);
		
	}

	/**
	 * Java 11
	 * @throws IOException
	 * @throws InterruptedException
	 */
	public void login3() throws IOException, InterruptedException {
		//make call to astrometry.bet website
			
		HttpClient client = HttpClient.newBuilder()
				.version(Version.HTTP_2)
				.build();

		LoginRequest loginReq = new LoginRequest();
		loginReq.setApikey("XXXXXXXX");
				
		HashMap<String, String> parameters = new HashMap<>();
		parameters.put("request-json", gson.toJson(loginReq));
		String form = parameters.keySet().stream()
		        .map(key -> key + "=" + URLEncoder.encode(parameters.get(key), StandardCharsets.UTF_8))
		        .collect(Collectors.joining("&"));
		
		HttpRequest request = HttpRequest.newBuilder()
				.headers("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(form))
				.uri(URI.create(loginURI))				
				.build();
		
		System.out.println("request body publisher:"+request.bodyPublisher().get().contentLength());
		System.out.println("request:"+request.toString());
		
		HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
		System.out.println("response body:"+response.body());
		System.out.println("response status code:"+response.statusCode());
		System.out.println("response header:"+response.headers());
		
		LoginResponse loginResponse = gson.fromJson(response.body(), LoginResponse.class);
		System.out.println("response:"+loginResponse);
		
	}	
	
	/**
	 * Apache http client
	 * @throws IOException
	 * @throws InterruptedException
	 */
	public void login2() throws IOException, InterruptedException {


		LoginRequest loginReq = new LoginRequest();
		loginReq.setApikey("XXXXXXXX");
		
		System.out.println("loginReq:"+gson.toJson(loginReq));
		
	    CloseableHttpClient client = HttpClients.createDefault();
	    HttpPost httpPost = new HttpPost(loginURI);
	 
	    List<NameValuePair> params = new ArrayList<NameValuePair>();
	    params.add(new BasicNameValuePair("request-json", gson.toJson(loginReq)));
	    httpPost.setEntity(new UrlEncodedFormEntity(params));
	 
	    CloseableHttpResponse response = client.execute(httpPost);

        HttpEntity entity = response.getEntity();
        if (entity != null) {
            // return it as a String
            String result = EntityUtils.toString(entity);
            System.out.println("response raw:"+result);
    		LoginResponse loginResponse = gson.fromJson(result, LoginResponse.class);
    		System.out.println("response parsed:"+loginResponse);                       
        }	  
        
	    client.close();		
	}
	
	public Future<PlateSolveResult> solve(File fitsFile) {
		//astrometry.net
		FutureTask<PlateSolveResult> task = new FutureTask<PlateSolveResult>(new Callable<PlateSolveResult>() {
			
			@Override
			public PlateSolveResult call() throws Exception {
				//map that stores the solve result 
				Map<String, String> solveResult = new HashMap<String, String>();
				
				//make call to astrometry.bet website
				URL url = new URL ("http://nova.astrometry.net/api/login");
				HttpURLConnection con = (HttpURLConnection)url.openConnection();
				
				//call astrometry.net
				PlateSolveResult ret = new PlateSolveResult(true, "", "", null);				
				return ret;
			}			
		});
		
		ExecutorService executor = Executors.newFixedThreadPool(1);
		executor.execute(task);
		return task;					
	}
}
