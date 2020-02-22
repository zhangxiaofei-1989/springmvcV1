package com.gupaoedu.mvcframework.v3.servlet;

import com.gupaoedu.mvcframework.annotation.*;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.*;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 此类作为启动入口
 * @author Tom
 *
 */
public class GPDispatcherServlet extends HttpServlet{

	private static final String LOCATION = "contextConfigLocation";

	private Properties p = new Properties();

	private List<String> classNames = new ArrayList<String>();

	private Map<String,Object> ioc = new HashMap<String,Object>();

	private List<Hander> handerList=new ArrayList<Hander>();

	public GPDispatcherServlet(){ super(); }
	
	/**
	 * 初始化，加载配置文件
	 */
	public void init(ServletConfig config) throws ServletException {
		
		//1、加载配置文件
		doLoadConfig(config.getInitParameter(LOCATION));
		
		//2、扫描所有相关的类
		doScanner(p.getProperty("scanPackage"));
		
		//3、初始化所有相关类的实例，并保存到IOC容器中
		doInstance();
		
		//4、依赖注入
		doAutowired();
		//5、构造HandlerMapping
		initHandlerMapping();
		
		//6、等待请求，匹配URL，定位方法， 反射调用执行
		//调用doGet或者doPost方法
		
		//提示信息
		System.out.println("gupaoedu mvcframework is init");
	}

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		this.doPost(req,resp);
	}

	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		try {
			dodispatch(req,resp);
		} catch (InvocationTargetException e) {
			e.printStackTrace();
		} catch (IllegalAccessException e) {
			e.printStackTrace();
		}
	}

	private void dodispatch(HttpServletRequest req, HttpServletResponse resp) throws InvocationTargetException, IllegalAccessException {
		Hander hander=getHander(req);
		try {
			if(hander==null){
				resp.getWriter().write("404");
			}
		}catch (Exception e){
			e.printStackTrace();
		}
		Class<?>[] parametypes=hander.getMethod().getParameterTypes();
		Object[] o=new Object[parametypes.length];
		Map<String, String[]> map=req.getParameterMap();
		for (Entry<String, String[]> entry :map.entrySet()){
			String value = Arrays.toString(entry.getValue()).replaceAll("\\[|\\]", "").replaceAll(",\\s", ",");

			if(!hander.paramIndexMapping.containsKey(entry.getKey())){
				continue;
			}
			o[hander.paramIndexMapping.get(entry.getKey())]=value;
		}

		if(hander.paramIndexMapping.containsKey(HttpServletRequest.class.getName())){
			o[hander.paramIndexMapping.get(HttpServletRequest.class.getName())]=req;
		}

		if(hander.paramIndexMapping.containsKey(HttpServletResponse.class.getName())){
			o[hander.paramIndexMapping.get(HttpServletResponse.class.getName())]=resp;
		}
		//调用方法
		hander.getMethod().invoke(hander.getInstance(),o);
	}

	private Hander getHander(HttpServletRequest req) {
	String url=	req.getRequestURI();
	String path=	req.getContextPath();
		url=	url.replace(path,"");
		for (Hander hander:handerList){
			Matcher matcher=hander.getUrl().matcher(url);
			if(matcher.matches()){
				return hander;
			}
		}
		return null;
	}

	private void initHandlerMapping() {

		if(ioc.isEmpty()){
			return;
		}
		for (Entry<String, Object> entry :ioc.entrySet()) {
		Object o=	entry.getValue();
		if(o.getClass().isAnnotationPresent(GPController.class)) {continue;}
		String url="";
		//获取contrller上面的URL
			GPController gpController=	o.getClass().getAnnotation(GPController.class);
			url=gpController.value();
		Method[] arrayMethod=o.getClass().getMethods();
		for (Method method:arrayMethod){
			if(!method.isAnnotationPresent(GPRequestMapping.class)){
				continue;
			}
			GPRequestMapping gpRequestMapping=	method.getAnnotation(GPRequestMapping.class);
			 String regex=("/"+url+"/"+gpRequestMapping.value()).replaceAll("/+","/");
			 Pattern pattern=Pattern.compile(regex);
			handerList.add(new Hander(pattern,method,entry.getValue()));
			System.out.println("mapping " + regex + "," + method);


		}

		}


	}

	private void doAutowired() {
		if(ioc.isEmpty()){return;}

		for (Entry<String, Object> entry :ioc.entrySet()) {
		Field[] fields=	entry.getValue().getClass().getDeclaredFields();
		for (Field filed:fields){
			if(!filed.isAnnotationPresent(GPAutowired.class)){return;}
			GPAutowired gpAutowired=	filed.getAnnotation(GPAutowired.class);
			String beanName=gpAutowired.value().trim();
			if("".equals(beanName)){
				beanName=filed.getType().getName();
			}
			filed.setAccessible(true);
			try {
				filed.set(entry.getValue(),ioc.get(beanName));
			}catch(Exception e){
				e.printStackTrace();
				continue;
			}

		}
		}

	}

	private void doInstance() {
		if(classNames.size()==0){
			return;
		}
		try {
			for (String className:classNames ) {
				Class<?> clas=	Class.forName(className);
				if(clas.isAnnotationPresent(GPController.class) ){
					String beanName=lowerFirst(clas.getSimpleName());
					ioc.put(beanName,clas.newInstance());
				}else if(clas.isAnnotationPresent(GPService.class)){
					GPService gpService=clas.getAnnotation(GPService.class);
					//如果用户自己设置了名字，
					if(!"".equals(gpService.value())){
						ioc.put(gpService.value(),clas.newInstance());
						continue;
					}
					//如果没有设置，就按接口类型创建一个实例
					for (Class<?> c :clas.getInterfaces()) {
						ioc.put(c.getName(),clas.newInstance());
					}


				}else{
					continue;
				}
			}

		}catch( Exception e){
			e.printStackTrace();

		}

	}

	private String lowerFirst(String simpleName) {
	char[] c=	simpleName.toCharArray();
	c[0]+=32;
	return String.valueOf(c);
	}

	private void doScanner(String scanPackage) {
	URL url=this.getClass().getClassLoader().getResource("/"+scanPackage.replaceAll("\\.","/"));
	File file=new File(url.getFile());
	for (File file1:file.listFiles()){
		if(file1.isDirectory()){
			doScanner(scanPackage+"."+file1.getName());
		}else{
			classNames.add(scanPackage+"."+scanPackage.replace(".class","").trim());
		}
	}

	}

	private void doLoadConfig(String initParameter) {
		try {
			FileInputStream f= (FileInputStream) this.getClass().getClassLoader().getResourceAsStream(initParameter);
			p.load(f);
		}catch (Exception e){
			e.printStackTrace();
		}
	}

	private class Hander {
		private Pattern url;
		private Method method;
		private Object instance;
		private Map<String, Integer> paramIndexMapping;

		public Hander(Pattern url, Method method, Object instance) {
			this.url = url;
			this.method = method;
			this.instance = instance;
			putParamIndexMapping(method);
		}

		private void putParamIndexMapping(Method method) {
			Annotation[][] annotations=	method.getParameterAnnotations();
			for(int i=0;i<=annotations.length;i++){
				for (Annotation annotation:annotations[i]) {
					if(annotation instanceof GPRequestParam){
					String paraName=	((GPRequestParam) annotation).value();
					if(!"".equals(paraName)){
						paramIndexMapping.put(paraName,i);
					}
					}

				}

			}
			Class<?>[] classes=	method.getParameterTypes();
			for(int i=0;i<classes.length;i++){
				if(classes[i]==HttpServletRequest.class||classes[i]==HttpServletResponse.class){
					paramIndexMapping.put(classes[i].getName(),i);
				}
			}





		}

		public Pattern getUrl() {
			return url;
		}

		public Method getMethod() {
			return method;
		}

		public Object getInstance() {
			return instance;
		}
	}



}
