package com.fenqile.sizeof.support;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 使用当前API方式:
 * <p>1. 通过命令行执行自己的Main方法：java -javaagent:sizeof.jar <your main class>
 * <p>2. 在IDE中启动，JVM参数添加： -javaagent:target/sizeof.jar -XX:-UseCompressedOops,
 *    其中UseCompressedOops参数禁用指针压缩，可以不加。
 *
 * <p>使用当前API：
 * <pre>
 *     class TestClass {
 *         public static void main(String args){
 *             SizeOf sizeOf  = new SizeOf();
 *             sizeOf.sizeOf(new Integer(1));
 *         }
 *     }
 * </pre>
 *
 * 或者采用更简洁的方式：
 *<pre>
 *     import static com.fenqile.sizeof.support.SizeOfTool.*;
 *
 *     class TestClass {
 *         public static void main(String args){
 *             System.out.println(sizeOf(new Integer(1)));
 *         }
 *     }
 *</pre>
 *
 * @see com.fenqile.sizeof.support.SizeOfTool
 *
 * @author jason.shang
 */
public class SizeOf extends AbstractSizable {

    @Override
    protected long doSizeOf(Object object){

        final IdentityHashMap<Object, Object> visited = new IdentityHashMap<>();
        final LinkedBlockingDeque<Holder> analysing = new LinkedBlockingDeque<>();

        ExecutorService executor = Executors.newCachedThreadPool(new ThreadFactory() {
            AtomicLong ID = new AtomicLong();
            @Override
            public Thread newThread(Runnable r) {
                Thread run = new Thread(r, "sizof-thread-" + ID.getAndIncrement());
                return run;
            }
        });

        final AtomicLong count = new AtomicLong(0);
        final AtomicLong sized = new AtomicLong(0);

        BlockingQueue<Future<Holder>> futures = new LinkedBlockingQueue<>();
        long length = 0;
        try{
            sized.set(sizeOf(object, visited, analysing, count));
            while ( count.get() > 0 ){
                final Holder item = analysing.takeFirst();
                count.decrementAndGet();
                length++;
                Future<Holder> future = executor.submit(new Callable<Holder>() {
                    @Override
                    public Holder call() throws Exception {
                        try {
                            long size = sizeOf(item.object, visited, analysing, count);
                            sized.getAndAdd(size);
                        } catch (InterruptedException e) {

                        }
                        return item;
                    }
                });
                futures.add(future);
            }

            for(Future<Holder> future; length > 0 && (future = futures.take()) != null; future.get(), length--);

        }catch (InterruptedException e){

        } catch (ExecutionException e) {

        } finally {
            executor.shutdown();
        }

        return sized.get();
    }

    private long sizeOf(Object object, Map<Object, Object> visited, BlockingDeque analysing, AtomicLong count) throws InterruptedException {

        if(object == null || shouldSkip(object, visited)){
            return 0;
        }

        visited.put(object, null);

        // calculate primitive variables 、 member pointers and so on
        long size = instrumentation.getObjectSize(object);

        Class<?> parent = object.getClass();
        while (parent != null && parent != Object.class) {

            // recursive analysis array elements
            if(parent.isArray()){
                if(parent.getName().length() != 2){
                    int length = Array.getLength(object);
                    for(int i = 0; i < length; i++){
                        analysing.putLast(new Holder(Array.get(object, i), -1L));
                        count.getAndIncrement();
                    }
                }
                return size;
            }

            // recursive analysis map elements
            if(Map.class.isAssignableFrom(parent)){
                Map<Object, Object> pairs = (Map<Object, Object>)object;
                for(Map.Entry<Object, Object> pair : pairs.entrySet()){
                    analysing.putLast(new Holder(pair, -1L));
                    count.getAndIncrement();
                }
                return size;
            }

            long start = System.currentTimeMillis();

            Field[] fields = parent.getDeclaredFields();
            for(int i = 0; i < fields.length; i++){
               // ignore repeat calculate static fields
                Field field = fields[i];
                if(!Modifier.isStatic(field.getModifiers())){
                    // ignore repeat calculate primitive fields
                    if(field.getType().isPrimitive()) continue;
                    boolean savedAccessible = field.isAccessible();
                    try{
                        if(!savedAccessible){
                            field.setAccessible(true);
                        }
                        Object added = field.get(object);
                        if(added != null) {
                            analysing.putLast(new Holder(added, -1L));
                            count.getAndIncrement();
                        }
                    } catch (IllegalAccessException e) {
                        // ignore
                    } finally {
                        if(!savedAccessible) field.setAccessible(false);
                    }
                }
            }

//            System.out.println("枚举字段耗时：" + parent.getName() + " :" + (System.currentTimeMillis() - start));

            parent = parent.getSuperclass();

        }

        return size;
    }

    private boolean shouldSkip(Object object, Map<Object, Object> visited) {
        if(object instanceof String){
            String value = (String)object;
            if(object == value.intern()){
                return true;
            }
        }
        return visited.containsKey(object);
    }

    private static class Guard {

    }

    private static class Holder{

        public Holder(Object object){
            this.object = object;
        }

        public Holder(Object object, long size){
            this.object = object;
            this.size = size;
        }

        volatile long size;
        Object object;
    }
}
