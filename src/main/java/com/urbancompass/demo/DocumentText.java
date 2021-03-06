/**
 * Copyright (C) 2022 Urban Compass, Inc.
 */
package com.urbancompass.demo;
//Calls DetectDocumentText.
//Loads document from S3 bucket. Displays the document and bounding boxes around detected lines/words of text.

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;

import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.S3ObjectInputStream;
import com.amazonaws.services.textract.AmazonTextract;
import com.amazonaws.services.textract.AmazonTextractClientBuilder;
import com.amazonaws.services.textract.model.AnalyzeDocumentRequest;
import com.amazonaws.services.textract.model.AnalyzeDocumentResult;
import com.amazonaws.services.textract.model.Block;
import com.amazonaws.services.textract.model.BoundingBox;
import com.amazonaws.services.textract.model.Document;
import com.amazonaws.services.textract.model.FeatureType;
import com.amazonaws.services.textract.model.HumanLoopConfig;
import com.amazonaws.services.textract.model.HumanLoopDataAttributes;
import com.amazonaws.services.textract.model.Point;
import com.amazonaws.services.textract.model.Relationship;
import com.amazonaws.services.textract.model.S3Object;

/**
 * @author shiqi.rao
 */
public class DocumentText extends JPanel {
  private static final long serialVersionUID = 1L;

  BufferedImage image;
  AnalyzeDocumentResult result;
  Map<String, List<Block>> kvMap = null;

  public DocumentText(AnalyzeDocumentResult documentResult, BufferedImage bufImage) {
    super();
    result = documentResult; // Results of text detection.
    image = bufImage; // The image containing the document.
  }

  public static void main(String[] arg) throws Exception {
    // The S3 bucket and document
    String document = System.getProperty("document", "1641530461707.jpg");
    String bucket = System.getProperty("bucket", "shiqi-detection-test");
    String flowArn = System.getProperty("arn",
        "arn:aws:sagemaker:us-east-1:742593912130:flow-definition/shiqi-test2");

    AmazonS3 s3client = AmazonS3ClientBuilder.standard()
        .withEndpointConfiguration(
            new EndpointConfiguration("https://s3.amazonaws.com", "us-east-1"))
        .build();

    // Get the document from S3
    com.amazonaws.services.s3.model.S3Object s3object = s3client.getObject(bucket, document);
    S3ObjectInputStream inputStream = s3object.getObjectContent();
    BufferedImage image = ImageIO.read(inputStream);

    // Call DetectDocumentText
    EndpointConfiguration endpoint = new EndpointConfiguration(
        "https://textract.us-east-1.amazonaws.com", "us-east-1");
    AmazonTextract client = AmazonTextractClientBuilder.standard()
        .withEndpointConfiguration(endpoint).build();

    // Set HumanLoop
    HumanLoopConfig humanLoopConfig = new HumanLoopConfig();
    humanLoopConfig.setFlowDefinitionArn(flowArn);
    humanLoopConfig.setHumanLoopName("human-loop-name-" + Instant.now().getEpochSecond());
    HumanLoopDataAttributes humanLoopDataAttributes = new HumanLoopDataAttributes();
    humanLoopDataAttributes.setContentClassifiers(
        List.of("FreeOfAdultContent"));
    humanLoopConfig.setDataAttributes(humanLoopDataAttributes);

    AnalyzeDocumentRequest request = new AnalyzeDocumentRequest()
        .withHumanLoopConfig(humanLoopConfig)
        .withFeatureTypes(FeatureType.FORMS)
        .withDocument(
            new Document().withS3Object(new S3Object().withName(document).withBucket(bucket)));

    AnalyzeDocumentResult result = client.analyzeDocument(request);

    // Create frame and panel.
    JFrame frame = new JFrame("RotateImage");
    frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    DocumentText panel = new DocumentText(result, image);
    panel.setPreferredSize(new Dimension(image.getWidth(), image.getHeight()));
    panel.setAutoscrolls(true);
    JScrollPane sp = new JScrollPane(panel);
    sp.setBounds(0, 0, 500, 500);
    frame.getContentPane().add(sp);
    frame.pack();
    frame.setVisible(true);
  }

  // Draws the image and text bounding box.
  public void paintComponent(Graphics g) {
    int height = image.getHeight(this);
    int width = image.getWidth(this);

    Graphics2D g2d = (Graphics2D)g; // Create a Java2D version of g.

    // Draw the image.
    g2d.drawImage(image, 0, 0, image.getWidth(this), image.getHeight(this), this);
    drawBoundingBoxWithCondition(height, width, g2d,
        k -> k.toLowerCase(Locale.ROOT)
            .contains(("Signature of Buyer(s)").toLowerCase(
                Locale.ROOT).replaceAll(" ", "")));
  }

  private void drawBoundingBoxWithCondition(int height, int width, Graphics2D g2d,
      Predicate<String> condition) {
    if (kvMap == null) {
      kvMap = relationshipMap();
    }
    kvMap.forEach((k, v) -> {
      if (condition.test(k)) {
        for (Block block : v) {
          displayBlockInfo(block);
          showBoundingBox(height, width, block.getGeometry().getBoundingBox(), g2d,
              block.getConfidence().intValue() + "");
        }
      }
    });
  }

  private Map<String, List<Block>> relationshipMap() {
    Map<String, List<Block>> map = new HashMap<>();
    Map<String, Block> blockMap = result.getBlocks().stream()
        .collect(Collectors.toMap(Block::getId, Function.identity()));
    List<Block> keyRefBlock = result.getBlocks().stream()
        .filter(block -> block.getBlockType().equals("KEY_VALUE_SET"))
        .filter(block -> block.getEntityTypes().contains("KEY"))
        .collect(Collectors.toList());
    for (Block refK : keyRefBlock) {
      List<Block> keyBlocks = refK.getRelationships().stream()
          .filter(relationship -> relationship.getType().equals("CHILD"))
          .flatMap(relationship -> relationship.getIds().stream())
          .map(blockMap::get)
          .collect(Collectors.toList());
      String keyText = keyBlocks.stream()
          .map(Block::getText)
          .collect(Collectors.joining());
      List<Block> valueBlocks = refK.getRelationships().stream()
          .filter(relationship -> relationship.getType().equals("VALUE"))
          .flatMap(relationship -> relationship.getIds().stream())
          .map(blockMap::get)
          .filter(block -> block.getRelationships() != null)
          .flatMap(block -> block.getRelationships().stream())
          .filter(relationship -> relationship.getType().equals("CHILD"))
          .flatMap(relationship -> relationship.getIds().stream())
          .map(blockMap::get)
          .collect(Collectors.toList());
      List<Block> former = map.get(keyText);
      if(former == null){
        map.put(keyText, valueBlocks);
      }else {
        former.addAll(valueBlocks);
      }
    }
    return map;
  }

  // Show bounding box at supplied location.
  private void showBoundingBox(int imageHeight, int imageWidth, BoundingBox box, Graphics2D g2d,
      String confidence) {

    float left = imageWidth * box.getLeft();
    float top = imageHeight * box.getTop();

    // Display bounding box.
    g2d.setColor(new Color(0, 212, 0));
    g2d.drawRect(Math.round(left), Math.round(top),
        Math.round(imageWidth * box.getWidth()), Math.round(imageHeight * box.getHeight()));
    g2d.drawString(confidence, Math.round(left), Math.round(top));
  }

  // Shows polygon at supplied location
  private void showPolygon(int imageHeight, int imageWidth, List<Point> points, Graphics2D g2d) {

    g2d.setColor(new Color(0, 0, 0));
    Polygon polygon = new Polygon();

    // Construct polygon and display
    for (Point point : points) {
      polygon.addPoint((Math.round(point.getX() * imageWidth)),
          Math.round(point.getY() * imageHeight));
    }
    g2d.drawPolygon(polygon);
  }

  // Draws only the vertical lines in the supplied polygon.
  private void showPolygonVerticals(int imageHeight, int imageWidth, List<Point> points,
      Graphics2D g2d) {

    g2d.setColor(new Color(0, 212, 0));
    Object[] parry = points.toArray();
    g2d.setStroke(new BasicStroke(2));

    g2d.drawLine(Math.round(((Point)parry[0]).getX() * imageWidth),
        Math.round(((Point)parry[0]).getY() * imageHeight),
        Math.round(((Point)parry[3]).getX() * imageWidth),
        Math.round(((Point)parry[3]).getY() * imageHeight));

    g2d.setColor(new Color(255, 0, 0));
    g2d.drawLine(Math.round(((Point)parry[1]).getX() * imageWidth),
        Math.round(((Point)parry[1]).getY() * imageHeight),
        Math.round(((Point)parry[2]).getX() * imageWidth),
        Math.round(((Point)parry[2]).getY() * imageHeight));
  }

  //Displays information from a block returned by text detection and text analysis
  private void displayBlockInfo(Block block) {
    System.out.println("Block Id : " + block.getId());
    if (block.getText() != null) {System.out.println("    Detected text: " + block.getText());}
    System.out.println("    Type: " + block.getBlockType());

    if (!block.getBlockType().equals("PAGE")) {
      System.out.println("    Confidence: " + block.getConfidence().toString());
    }
    if (block.getBlockType().equals("CELL")) {
      System.out.println("    Cell information:");
      System.out.println("        Column: " + block.getColumnIndex());
      System.out.println("        Row: " + block.getRowIndex());
      System.out.println("        Column span: " + block.getColumnSpan());
      System.out.println("        Row span: " + block.getRowSpan());
    }

    System.out.println("    Relationships");
    List<Relationship> relationships = block.getRelationships();
    if (relationships != null) {
      for (Relationship relationship : relationships) {
        System.out.println("        Type: " + relationship.getType());
        System.out.println("        IDs: " + relationship.getIds().toString());
      }
    } else {
      System.out.println("        No related Blocks");
    }

    System.out.println("    Geometry");
    System.out.println("        Bounding Box: " + block.getGeometry().getBoundingBox().toString());
    System.out.println("        Polygon: " + block.getGeometry().getPolygon().toString());

    List<String> entityTypes = block.getEntityTypes();

    System.out.println("    Entity Types");
    if (entityTypes != null) {
      for (String entityType : entityTypes) {
        System.out.println("        Entity Type: " + entityType);
      }
    } else {
      System.out.println("        No entity type");
    }
    if (block.getPage() != null) {System.out.println("    Page: " + block.getPage());}
    System.out.println();
  }
}

