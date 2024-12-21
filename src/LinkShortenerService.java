package com.shortener;

import java.time.Instant;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.net.URI;
import java.awt.Desktop;

public class LinkShortenerService {

    private final Map<String, Link> linkStorage = new ConcurrentHashMap<>();
    private final Map<String, String> userUUIDs = new HashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    public LinkShortenerService() {
        scheduler.scheduleAtFixedRate(this::removeExpiredLinks, 1, 1, TimeUnit.HOURS);
    }

    public String createShortLink(String originalUrl, String userId, int maxClicks, Duration ttl) {
        if (maxClicks <= 0 || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("Максимальное количество переходов и время жизни должны быть больше 0.");
        }

        String userUUID = userUUIDs.computeIfAbsent(userId, k -> UUID.randomUUID().toString());
        String shortLinkId = generateShortLinkId(originalUrl, userUUID);
        Instant expiryTime = Instant.now().plus(ttl);

        Link link = new Link(originalUrl, shortLinkId, userUUID, maxClicks, expiryTime);
        linkStorage.put(shortLinkId, link);

        return shortLinkId;
    }

    public String resolveLink(String shortLinkId) throws Exception {
        Link link = linkStorage.get(shortLinkId);

        if (link == null) {
            throw new Exception("Ссылка не существует.");
        }

        if (link.isExpired()) {
            linkStorage.remove(shortLinkId);
            throw new Exception("Срок действия ссылки истёк.");
        }

        if (link.isLimitExceeded()) {
            throw new Exception("Ссылка достигла максимального количества использования.");
        }

        link.incrementClicks();
        return link.getOriginalUrl();
    }

    private void removeExpiredLinks() {
        Instant now = Instant.now();
        linkStorage.values().removeIf(link -> link.getExpiryTime().isBefore(now));
    }

    private String generateShortLinkId(String originalUrl, String userUUID) {
        return Base62.encode((originalUrl + userUUID + Instant.now().toString()).hashCode());
    }

    public void openInBrowser(String shortLinkId) throws Exception {
        String resolvedUrl = resolveLink(shortLinkId);
        Desktop.getDesktop().browse(new URI(resolvedUrl));
    }

    public List<Link> getUserLinks(String userId) {
        String userUUID = userUUIDs.get(userId);
        if (userUUID == null) {
            return Collections.emptyList();
        }

        List<Link> userLinks = new ArrayList<>();
        for (Link link : linkStorage.values()) {
            if (link.getUserUUID().equals(userUUID)) {
                userLinks.add(link);
            }
        }
        return userLinks;
    }

    public void deleteLink(String shortLinkId) {
        linkStorage.remove(shortLinkId);
    }

    public static void main(String[] args) {
        LinkShortenerService service = new LinkShortenerService();
        Scanner scanner = new Scanner(System.in);

        System.out.println("Добро пожаловать в сервис сокращения ссылок!");

        while (true) {
            System.out.println("Опции: [1] Создать ссылку [2] Перейти по ссылке [3] Посмотреть ссылки пользователя [4] Удалить ссылку [5] Выйти");
            int choice = scanner.nextInt();
            scanner.nextLine(); // Consume newline

            try {
                if (choice == 1) {
                    System.out.print("Введите оригинальный URL: ");
                    String url = scanner.nextLine();

                    System.out.print("Введите ID пользователя: ");
                    String userId = scanner.nextLine();

                    System.out.print("Введите максимальное количество переходов: ");
                    int maxClicks = scanner.nextInt();

                    System.out.print("Введите TTL в секундах: ");
                    long ttlSeconds = scanner.nextLong();
                    scanner.nextLine(); // Consume newline

                    String shortLink = service.createShortLink(url, userId, maxClicks, Duration.ofSeconds(ttlSeconds));
                    System.out.println("Короткая ссылка создана: " + shortLink);
                } else if (choice == 2) {
                    System.out.print("Введите ID короткой ссылки: ");
                    String shortLinkId = scanner.nextLine();
                    service.openInBrowser(shortLinkId);
                } else if (choice == 3) {
                    System.out.print("Введите ID пользователя: ");
                    String userId = scanner.nextLine();
                    List<Link> links = service.getUserLinks(userId);

                    if (links.isEmpty()) {
                        System.out.println("Для этого пользователя ссылки не найдены.");
                    } else {
                        System.out.println("Ссылки для пользователя " + userId + ":");
                        for (Link link : links) {
                            System.out.println("- " + link.getShortLinkId() + " -> " + link.getOriginalUrl());
                        }
                    }
                } else if (choice == 4) {
                    System.out.print("Введите ID короткой ссылки для удаления: ");
                    String shortLinkId = scanner.nextLine();
                    service.deleteLink(shortLinkId);
                    System.out.println("Ссылка удалена, если она существовала.");
                } else if (choice == 5) {
                    System.out.println("До свидания!");
                    break;
                } else {
                    System.out.println("Недопустимый выбор. Попробуйте снова.");
                }
            } catch (Exception e) {
                System.err.println("Ошибка: " + e.getMessage());
            }
        }

        scanner.close();
        service.scheduler.shutdown();
    }
}

class Link {
    private final String originalUrl;
    private final String shortLinkId;
    private final String userUUID;
    private final int maxClicks;
    private final Instant expiryTime;
    private int clickCount;

    public Link(String originalUrl, String shortLinkId, String userUUID, int maxClicks, Instant expiryTime) {
        this.originalUrl = originalUrl;
        this.shortLinkId = shortLinkId;
        this.userUUID = userUUID;
        this.maxClicks = maxClicks;
        this.expiryTime = expiryTime;
        this.clickCount = 0;
    }

    public String getOriginalUrl() {
        return originalUrl;
    }

    public String getShortLinkId() {
        return shortLinkId;
    }

    public String getUserUUID() {
        return userUUID;
    }

    public Instant getExpiryTime() {
        return expiryTime;
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiryTime);
    }

    public boolean isLimitExceeded() {
        return clickCount >= maxClicks;
    }

    public void incrementClicks() {
        this.clickCount++;
    }
}

class Base62 {
    private static final String CHARSET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    public static String encode(int value) {
        StringBuilder encoded = new StringBuilder();
        int base = CHARSET.length();

        while (value > 0) {
            encoded.insert(0, CHARSET.charAt(value % base));
            value /= base;
        }

        return encoded.toString();
    }
}
