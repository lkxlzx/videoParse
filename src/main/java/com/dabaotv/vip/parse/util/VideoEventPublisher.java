package com.dabaotv.vip.parse.util;

import com.alibaba.fastjson.JSONObject;
import com.dabaotv.vip.parse.dto.VideoUrl;
import com.dabaotv.vip.parse.repository.VideoRepository;
import com.qiniu.common.QiniuException;
import com.qiniu.http.Response;
import com.qiniu.storage.Configuration;
import com.qiniu.storage.Region;
import com.qiniu.storage.UploadManager;
import com.qiniu.util.Auth;
import com.qiniu.util.StringUtils;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.UUID;

/**
 * @author 周子斐
 * parse
 * @date 2020/7/24
 * @Description
 */
@Component
public class VideoEventPublisher {


    @Autowired
    RedisUtils redisUtils;

    @Autowired
    VideoRepository videoRepository;


    /**
     * 新增券流水
     */
    @RabbitListener(queues = Queues.SAVE)
    public void saveCoupFlowEvent(VideoUrl videoUrl) {
        String originalUrl = videoUrl.getOriginalUrl();
        saveM3u8(videoUrl,originalUrl);
        videoRepository.save(videoUrl);
        videoUrl.setUrl("http://qdylo2p39.bkt.clouddn.com/" + videoUrl.getUrl());
        String s = JSONObject.toJSONString(videoUrl);
        redisUtils.set(originalUrl, s);
    }

    private void saveM3u8(VideoUrl videoUrl, String originalUrl) {
        String domain = null;
        if (videoUrl.getDomain() != null) {
            domain = videoUrl.getDomain();
        }
        videoUrl.setOriginalUrl(originalUrl);
        String parseUrl = videoUrl.getUrl();
        if (originalUrl.contains(VideoType.CCTV.getType())) {
            //cctv,可以解析  去掉最后一个/后的拼播放地址就行
            int i = parseUrl.lastIndexOf("/");
            String substring = parseUrl.substring(0, i);
            videoUrl.setPrefixUrl(substring + "/");
        }
        //搜狐/优酷/爱奇艺/咪咕
        String playUrl = saveLocal(parseUrl, domain, videoUrl.getPrefixUrl());
        videoUrl.setUrl(playUrl);
    }

    public String saveLocal(String url, String domain, String prefixUrl) {

        //构造一个带指定 Region 对象的配置类
        Configuration cfg = new Configuration(Region.region0());
        //...其他参数参考类注释
        UploadManager uploadManager = new UploadManager(cfg);
        //...生成上传凭证，然后准备上传
        String accessKey = "EQSTit4nxmAmnN-uGiufJXVrGb1F8FzAym89lrsI";
        String secretKey = "simNyXeM2QaxmIU97549RMNat1dNU4uF2WoizkgA";
        String bucket = "m3u8play";
        //默认不指定key的情况下，以文件内容的hash值作为文件名
        String replace = UUID.randomUUID().toString().replace("-", "");
        String key = replace + ".m3u8";
        try {
            URL file = new URL(url);
            URLConnection urlConnection = file.openConnection();
            HttpURLConnection connection = (HttpURLConnection) urlConnection;
            // 设定请求的方法，默认是GET
            connection.setRequestMethod("GET");
            // 设置字符编码
            connection.setRequestProperty("User-Agent", "Mozilla/4.0 (compatible;MSIE 7.0; Windows NT 5.1; Maxthon;)");
            connection.setRequestProperty("Accept-Encoding", "gzip");
            connection.setRequestProperty("referer", domain);
            connection.setRequestProperty("cookie", "http://control.blog.sina.com.cn");
            // 打开到此 URL 引用的资源的通信链接（如果尚未建立这样的连接）。
            connection.connect();
            InputStream inputStream = connection.getInputStream();
            if (!StringUtils.isNullOrEmpty(prefixUrl)) {
                StringBuilder result = new StringBuilder();
                //构造一个BufferedReader类来读取文件
                BufferedReader br = new BufferedReader(new InputStreamReader(inputStream));
                String s = null;
                //使用readLine方法，一次读一行
                while (((s = br.readLine()) != null)) {
                    if (!s.contains("#")) {
                        result.append(prefixUrl + s + "\n");
                    } else {
                        result.append(s + "\n");
                    }
                }
                inputStream = new ByteArrayInputStream(result.toString().getBytes());
            }
            Auth auth = Auth.create(accessKey, secretKey);
            String upToken = auth.uploadToken(bucket);
            try {
                Response response = uploadManager.put(inputStream, key, upToken, null, null);
                //解析上传成功的结果
                JSONObject jsonObject = JSONObject.parseObject(response.bodyString());
                System.out.println(jsonObject);
                return (String) jsonObject.get("key");
            } catch (QiniuException ex) {
                Response r = ex.response;
                System.err.println(r.toString());
                try {
                    System.err.println(r.bodyString());
                } catch (QiniuException ex2) {
                    //ignore
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return url;
    }
}