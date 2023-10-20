import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Main {
    // will use this part of all threads.
    static final float THREADS_FACTOR = 0.5f;
    static final String FILES_DIR = "files/";
    static final String LOG_DIR = "log_result.txt";

    static FileWriter log_writer;
    static Pattern patternDownloadUrl = Pattern.compile("\"downloadUrl\":\\s*\"([^\"]+)\"");

    public static void main(String[] args) throws IOException {
        try {
            log_writer = new FileWriter(LOG_DIR);
        } catch (IOException e) {
            System.out.printf("Error creation log writer: %s\r\n", e.getMessage());
        }

        // all urls from manifest file.
        String[] urls = getUrlsFromManifest().toArray(new String[0]);

        // amount of available threads;
        int threads_amount = Runtime.getRuntime().availableProcessors();

        // amount of threads to be used.
        int threads_amount_to_work = (int) Math.max(1, Math.floor(threads_amount * THREADS_FACTOR));

        // length of arrays for equal load on threads.
        int length_subarrays = (int) Math.ceil(urls.length / (float) threads_amount_to_work);

        // creating folder for files (if not exist).
        new File(FILES_DIR).mkdir();

        // creating task for get success/failed urls.
        FutureTask<LoadFileThreadResult>[] tasks = new FutureTask[threads_amount_to_work];

        // creating threads.
        for (int i = 0; i < threads_amount_to_work; i++) {
            int from = i * length_subarrays;
            int to = from + length_subarrays;

            if (from > urls.length) break;

            String[] some_urls = Arrays.copyOfRange(urls, from, to);

            System.out.println(Arrays.toString(some_urls));

            FutureTask<LoadFileThreadResult> task = new FutureTask<>(new LoadFilesThread(some_urls, FILES_DIR));
            tasks[i] = task;

            Thread thread = new Thread(task);
            thread.start();
        }

        ArrayList<String> success_url = new ArrayList<>();
        ArrayList<String> failed_url = new ArrayList<>();

        // sum the threads results.
        for (FutureTask<LoadFileThreadResult> task : tasks) {
            try {
                if (task == null) continue;
                LoadFileThreadResult task_result = task.get();

                success_url.addAll(task_result.success);
                failed_url.addAll(task_result.failed);

            } catch (InterruptedException | ExecutionException e) {
                System.out.printf("Error on getting value from future task: %s", e.getMessage());
            }
        }

        // creating log file.
        if (log_writer != null) {
            SimpleDateFormat sdf = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss");
            Date date = new Date(System.currentTimeMillis());

            log_writer.write(String.format("File created: %s\r\n\r\n", sdf.format(date)));

            log_writer.write(String.format("Statistics: %d SUCCESS - %d FAILED \r\n\r\n", success_url.size(), failed_url.size()));
            log_writer.write("FAILED: \r\n");

            for (String url : failed_url) {
                log_writer.write(String.format("\t%s,\r\n", url));
            }

            log_writer.write("SUCCESS: \r\n");

            for (String url : success_url) {
                log_writer.write(String.format("\t%s,\r\n", url));
            }

            log_writer.close();
        }
    }

    public static ArrayList<String> getUrlsFromManifest() {
        ArrayList<String> urls = new ArrayList<>(128);

        try (BufferedReader reader = new BufferedReader(new FileReader("manifest.json"))) {
            String line;

            while (true) {
                line = reader.readLine();
                if (line == null) break;

                Matcher matcher = patternDownloadUrl.matcher(line);

                if (matcher.find()) {
                    try {
                        urls.add(matcher.group(1));
                    } catch (Exception e) {
                        System.out.printf("Error while reading manifest: %s\r\n", e.getMessage());
                    }
                }
            }

        } catch (IOException e) {
            System.out.printf("Error opening the manifest file: %s\r\n", e.getMessage());
        }

        return urls;
    }
}

class LoadFilesThread implements Callable<LoadFileThreadResult> {
    static final Pattern patternFileName = Pattern.compile("[^/]*$");

    String filesFolder;
    String[] urls;

    LoadFileThreadResult result = new LoadFileThreadResult();

    public LoadFilesThread(String[] urls, String filesDir) {
        this.urls = urls;
        this.filesFolder = filesDir;
    }

    @Override
    public LoadFileThreadResult call() {
        for (String url : urls) {
            if (url == null) continue;
            boolean loadResult = LoadFilesThread.loadFile(url, filesFolder);

            if (loadResult) {
                System.out.printf("File %s successfully downloaded!\r\n", url);
                result.success.add(url);
            } else {
                System.out.printf("*** File %s failed to download!\r\n", url);
                result.failed.add(url);
            }
        }

        return result;
    }

    public static boolean loadFile(String url, String filesDir) {
        Matcher matcher = patternFileName.matcher(url);
        String fileName;

        if (matcher.find()) {
            fileName = matcher.group(0);
        } else {
            fileName = "unmatched_" + System.nanoTime() + ".jar";
        }

        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .build();

            HttpResponse<InputStream> response;

            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

                if (response.statusCode() == 302) { // if redirected
                    request = HttpRequest.newBuilder()
                            .uri(URI.create(response.headers().map().get("location").get(0))) // get url to redirected
                            .build();

                    response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                }
            } catch (InterruptedException e) {
                return false;
            }

            try (InputStream inputStream = response.body()) {
                FileOutputStream fileOutputStream = new FileOutputStream(filesDir + fileName);

                byte[] buffer = new byte[1024];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    fileOutputStream.write(buffer, 0, bytesRead);
                }

                fileOutputStream.close();
            } catch (Exception e) {
                return false;
            }

        } catch (IOException e) {
            System.out.printf("Client creation error: %s\r\n", e.getMessage());
            return false;
        } catch (IllegalArgumentException e) {
            return false;
        }

        return true;
    }
}

class LoadFileThreadResult {
    public ArrayList<String> success = new ArrayList<>();
    public ArrayList<String> failed = new ArrayList<>();
}