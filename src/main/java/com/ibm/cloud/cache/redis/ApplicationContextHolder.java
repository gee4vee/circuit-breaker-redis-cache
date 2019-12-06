package com.ibm.cloud.cache.redis;

import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

/**
 * Used to access Spring-instantiated beans.
 */
@Component
public class ApplicationContextHolder implements ApplicationContextAware {
	
    private static ApplicationContext context;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        setAppCtx(applicationContext);   
    }
    
    private static synchronized void setAppCtx(ApplicationContext applicationContext) {
    	if (context == null) {
    		context = applicationContext;
    	}
    }

    public static ApplicationContext getContext() {
        return context;
    }
}