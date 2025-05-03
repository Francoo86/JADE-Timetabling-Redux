package debugscreens;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;

public class ProfessorDebugViewer extends JFrame {
    private final JTable scheduleTable;
    private final JLabel statsLabel;
    private final String professorName;

    public ProfessorDebugViewer(String name) {
        super("Schedule: " + name);
        this.professorName = name;

        setSize(800, 400);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

        JPanel mainPanel = new JPanel(new BorderLayout(5, 5));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        statsLabel = new JLabel("Active: " + name);
        statsLabel.setFont(new Font("Arial", Font.BOLD, 12));
        mainPanel.add(statsLabel, BorderLayout.NORTH);

        String[] columnNames = {"Block", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday"};
        DefaultTableModel model = new DefaultTableModel(columnNames, 9);
        scheduleTable = new JTable(model) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        // Custom renderer for color coding
        scheduleTable.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(
                    JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int col) {

                Component c = super.getTableCellRendererComponent(
                        table, value, isSelected, hasFocus, row, col);

                if (col == 0) {
                    setBackground(new Color(240, 240, 240));
                    setText(String.valueOf(row + 1));
                } else if (value != null && !value.toString().isEmpty()) {
                    String text = value.toString();
                    if (text.contains("K")) {
                        setBackground(new Color(225, 235, 255));
                    } else if (text.contains("CM")) {
                        setBackground(new Color(225, 255, 235));
                    } else if (text.contains("IC")) {
                        setBackground(new Color(255, 250, 225));
                    } else if (text.contains("ANT")) {
                        setBackground(new Color(240, 225, 255));
                    } else {
                        setBackground(Color.WHITE);
                    }
                } else {
                    setBackground(Color.WHITE);
                }

                return c;
            }
        });

        scheduleTable.setRowHeight(40);
        scheduleTable.getColumnModel().getColumn(0).setPreferredWidth(50);
        for (int i = 1; i < scheduleTable.getColumnCount(); i++) {
            scheduleTable.getColumnModel().getColumn(i).setPreferredWidth(140);
        }

        mainPanel.add(new JScrollPane(scheduleTable), BorderLayout.CENTER);
        setContentPane(mainPanel);

        // Position window based on professor's order
        setLocationByName(name);
    }

    private void setLocationByName(String name) {
        GraphicsDevice gd = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        int screenWidth = gd.getDisplayMode().getWidth();
        int screenHeight = gd.getDisplayMode().getHeight();

        int hash = Math.abs(name.hashCode());
        int x = (hash % (screenWidth - getWidth())) / 2;
        int y = ((hash / screenWidth) % (screenHeight - getHeight())) / 2;

        setLocation(x, y);
    }

    public void updateSchedule(JSONObject horarioJSON, int completedSubjects, int totalSubjects) {
        if (horarioJSON == null) {
            System.err.println("Error: horarioJSON is null for " + professorName);
            return;
        }

        JSONArray asignaturas = (JSONArray) horarioJSON.get("Asignaturas");
        if (asignaturas == null) {
            System.err.println("Error: No Asignaturas array found for " + professorName);
            return;
        }

        statsLabel.setText(String.format("Active: %s - Progress: %d/%d subjects - Assignments: %d",
                professorName, completedSubjects, totalSubjects, asignaturas.size()));

        // Clear table
        DefaultTableModel model = (DefaultTableModel) scheduleTable.getModel();
        for (int i = 0; i < 9; i++) {
            for (int j = 0; j < 6; j++) {
                model.setValueAt("", i, j);
            }
        }

        // Debug print each assignment
        System.out.println("\nUpdating schedule for " + professorName);
        System.out.println("Number of assignments: " + asignaturas.size());

        // Fill schedule
        for (Object obj : asignaturas) {
            if (obj == null) {
                continue;
            }

            JSONObject asignatura = (JSONObject) obj;
            try {
                Number blockNum = (Number) asignatura.get("Bloque");
                int block = blockNum.intValue() - 1;
                String day = (String) asignatura.get("Dia");
                String name = (String) asignatura.get("Nombre");
                String sala = (String) asignatura.get("Sala");

                int col = getDayColumn(day);

                // Debug print
                System.out.printf("Assignment: Day=%s, Block=%d, Name=%s, Room=%s%n",
                        day, block + 1, name, sala);

                if (col > 0 && block >= 0 && block < 9) {
                    String cellText = String.format("<html>%s<br>%s</html>",
                            name.length() > 20 ? name.substring(0, 17) + "..." : name,
                            sala);
                    model.setValueAt(cellText, block, col);
                }
            } catch (Exception e) {
                System.err.println("Error processing assignment: " + asignatura);
                e.printStackTrace();
            }
        }

        repaint();
    }

    private int getDayColumn(String day) {
        switch (day) {
            case "Lunes": return 1;
            case "Martes": return 2;
            case "Miercoles": return 3;
            case "Jueves": return 4;
            case "Viernes": return 5;
            default: return -1;
        }
    }
}