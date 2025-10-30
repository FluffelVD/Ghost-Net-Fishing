package de.iu.ipwa.ghostnet.controller;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import de.iu.ipwa.ghostnet.Geisternetz;
import de.iu.ipwa.ghostnet.Person;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.SessionScoped;
// NEU: Import für FacesMessage
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Named;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;

@Named
@SessionScoped
public class NetController implements Serializable {

    private static final long serialVersionUID = 1L;

    private EntityManagerFactory emf;
    private Geisternetz newNet;
    private Geisternetz netToClaim;
    private Person salvager;
    private Person reporter;
    private boolean anonymous;
    private String userRole;

    @PostConstruct
    public void init() {
        emf = Persistence.createEntityManagerFactory("ghost-net-pu");
        resetReportForm();
    }

    private void resetReportForm() {
        newNet = new Geisternetz();
        reporter = new Person();
        anonymous = true; // Anonym ist der Standard
    }

    // ... (reportNet, prepareClaim, confirmClaim, etc. bleiben unverändert) ...
    
    // ==============================
    //  Bergung übernehmen & abschließen
    // ==============================
    
    // prepareClaim and confirmClaim sind unverändert
    public String prepareClaim(Geisternetz selectedNet) {
        this.netToClaim = selectedNet;
        this.salvager = new Person();
        return "claim.xhtml?faces-redirect=true";
    }

    public String confirmClaim() {
        EntityManager em = emf.createEntityManager();
        try {
            em.getTransaction().begin();
            em.persist(salvager);
            
            Geisternetz managedNet = em.find(Geisternetz.class, netToClaim.getId());
            if (managedNet != null) {
                managedNet.setSalvager(salvager);
                managedNet.setStatus("BERGUNG BEVORSTEHEND");
                em.merge(managedNet);
            }
            em.getTransaction().commit();
            
            salvager = new Person();
            netToClaim = null;
            return "dashboard.xhtml?faces-redirect=true";
        } catch (Exception e) {
             if (em.getTransaction().isActive()) em.getTransaction().rollback();
            return null;
        } finally {
            em.close();
        }
    }

    // GEÄNDERT: Methode erzeugt nun eine Erfolgsmeldung für die UI
    public String markAsRecovered(Geisternetz netToMark) {
        EntityManager em = emf.createEntityManager();
        try {
            em.getTransaction().begin();
            Geisternetz managedNet = em.find(Geisternetz.class, netToMark.getId());
            if (managedNet != null) {
                managedNet.setStatus("GEBORGEN");
                em.merge(managedNet);
                
                // NEU: Erstelle und füge eine Erfolgsmeldung hinzu
                String summary = "Erfolgreich!";
                String detail = "Netz #" + managedNet.getId() + " wurde als geborgen markiert.";
                FacesContext.getCurrentInstance().addMessage(null, 
                    new FacesMessage(FacesMessage.SEVERITY_INFO, summary, detail));
            }
            em.getTransaction().commit();
        } finally {
            em.close();
        }
        return null; // AJAX-Update, bleibe auf der Seite
    }

    // Der Rest der Klasse (getOpenNets, goHome, selectRole, Getter/Setter etc.) bleibt unverändert
    
    // ==============================
    //  Navigation & Rollenwahl
    // ==============================
    public String goHome() {
        return "index.xhtml?faces-redirect=true";
    }
    
    public String selectRole(String role) {
        this.userRole = role;
        return "index.xhtml?faces-redirect=true";
    }

    // ==============================
    //  Geisternetz melden
    // ==============================
    public String reportNet() {
        EntityManager em = emf.createEntityManager();
        try {
            em.getTransaction().begin();

            if (!anonymous) {
                em.persist(reporter);
                newNet.setReporter(reporter); 
            } else {
                newNet.setReporter(null); 
            }
            
            newNet.setSalvager(null);
            newNet.setStatus("GEMELDET");
            em.persist(newNet);
            
            em.getTransaction().commit();
            resetReportForm(); 
            return "confirmation.xhtml?faces-redirect=true";
        } catch (Exception e) {
            if (em.getTransaction().isActive()) em.getTransaction().rollback();
            return null;
        } finally {
            em.close();
        }
    }

    // ==============================
    //  Offene Geisternetze abrufen
    // ==============================
    public List<Geisternetz> getOpenNets() {
        EntityManager em = emf.createEntityManager();
        try {
            return em.createQuery(
                "SELECT g FROM Geisternetz g WHERE g.status = 'GEMELDET' OR g.status = 'BERGUNG BEVORSTEHEND'",
                Geisternetz.class
            ).getResultList().stream()
               .filter(distinctByKey(Geisternetz::getGpsCoordinates))
               .collect(Collectors.toList());
        } finally {
            em.close();
        }
    }
    
    // ==============================
    //  Hilfsmethoden & Getter/Setter
    // ==============================
    public String getOpenNetsAsJson() {
        List<Geisternetz> openNets = getOpenNets();
        StringBuilder json = new StringBuilder("[");
        Pattern p = Pattern.compile("-?\\d+(\\.\\d+)?");
        for (int i = 0; i < openNets.size(); i++) {
            Geisternetz net = openNets.get(i);
            Matcher m = p.matcher(net.getGpsCoordinates());
            String lat = m.find() ? m.group() : null;
            String lng = m.find() ? m.group() : null;
            if (lat != null && lng != null) {
                String safeSize = net.getEstimatedSize() != null ? net.getEstimatedSize().replace("\"", "\\\"") : "";
                String safeStatus = net.getStatus() != null ? net.getStatus().replace("\"", "\\\"") : "";
                json.append(String.format(java.util.Locale.US,
                    "{\"lat\": %s, \"lng\": %s, \"info\": \"<b>Netz ID: %d</b><br>Größe: %s<br>Status: %s\"}",
                    lat, lng, net.getId(), safeSize, safeStatus));
                if (i < openNets.size() - 1) json.append(",");
            }
        }
        json.append("]");
        return json.toString();
    }
    
    public static <T> Predicate<T> distinctByKey(Function<? super T, ?> keyExtractor) {
        Map<Object, Boolean> seen = new ConcurrentHashMap<>();
        return t -> seen.putIfAbsent(keyExtractor.apply(t), Boolean.TRUE) == null;
    }
    
    public Geisternetz getNewNet() { return newNet; }
    public Geisternetz getNetToClaim() { return netToClaim; }
    public Person getSalvager() { return salvager; }
    public boolean isAnonymous() { return anonymous; }
    public void setAnonymous(boolean anonymous) { this.anonymous = anonymous; }
    public Person getReporter() { return reporter; }
    public String getUserRole() { return userRole; }
    public void setUserRole(String userRole) { this.userRole = userRole; }
}